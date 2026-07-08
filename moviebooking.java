 import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class MovieBookingSystem {
    public static void main(String[] args) { Demo.run(); }
}

/* ===================== Enums & Exceptions ===================== */
enum SeatType { REGULAR, PREMIUM, RECLINER }
enum ShowSeatStatus { AVAILABLE, BOOKED }
enum BookingStatus { CREATED, CONFIRMED, EXPIRED, CANCELLED }
enum PaymentStatus { SUCCESS, FAILED }

class SeatUnavailableException extends RuntimeException {
    SeatUnavailableException(String m) { super(m); }
}

/* ===================== Domain ===================== */
class Movie {
    private final String id, title, language;
    private final int runtimeMinutes;
    Movie(String id, String title, String language, int runtimeMinutes) {
        this.id = id; this.title = title; this.language = language;
        this.runtimeMinutes = runtimeMinutes;
    }
    String getId() { return id; }
    String getTitle() { return title; }
}

class Seat {
    private final String id; private final int row, col; private final SeatType type;
    Seat(String id, int row, int col, SeatType type) {
        this.id = id; this.row = row; this.col = col; this.type = type;
    }
    String getId() { return id; }
    SeatType getType() { return type; }
}

class Screen {
    private final String id, name; private final List<Seat> seats;
    Screen(String id, String name, List<Seat> seats) {
        this.id = id; this.name = name; this.seats = seats;
    }
    String getId() { return id; }
    List<Seat> getSeats() { return Collections.unmodifiableList(seats); }
}

class Theatre {
    private final String id, name, city; private final List<Screen> screens;
    Theatre(String id, String name, String city, List<Screen> screens) {
        this.id = id; this.name = name; this.city = city; this.screens = screens;
    }
    String getId() { return id; }
    String getCity() { return city; }
}

class User {
    private final String id, name;
    User(String id, String name) { this.id = id; this.name = name; }
    String getId() { return id; }
    String getName() { return name; }
}

/* ===================== Strategy: Pricing ===================== */
interface PricingStrategy { double priceFor(Seat seat); }

class DefaultPricingStrategy implements PricingStrategy {
    private final Map<SeatType, Double> base;
    DefaultPricingStrategy(Map<SeatType, Double> base) { this.base = base; }
    public double priceFor(Seat seat) {
        return base.getOrDefault(seat.getType(), 200.0);
    }
}

/* ===================== Show & ShowSeat ===================== */
class ShowSeat {
    private final String id;          // unique within a show (== seat id here)
    private final Seat seat;
    private final double price;
    // volatile: search threads must observe BOOKED writes made under the show monitor.
    private volatile ShowSeatStatus status = ShowSeatStatus.AVAILABLE;
    ShowSeat(String id, Seat seat, double price) {
        this.id = id; this.seat = seat; this.price = price;
    }
    String getId() { return id; }
    double getPrice() { return price; }
    ShowSeatStatus getStatus() { return status; }
    void setStatus(ShowSeatStatus s) { this.status = s; }
}

class Show {
    private final String id;
    private final Movie movie;
    private final Theatre theatre;
    private final Screen screen;
    private final LocalDateTime startTime;
    private final Map<String, ShowSeat> showSeats = new LinkedHashMap<>();
    Show(String id, Movie movie, Theatre theatre, Screen screen,
         LocalDateTime startTime, PricingStrategy pricing) {
        this.id = id; this.movie = movie; this.theatre = theatre;
        this.screen = screen; this.startTime = startTime;
        // Build a ShowSeat per physical seat with a per-show price.
        for (Seat s : screen.getSeats())
            showSeats.put(s.getId(), new ShowSeat(s.getId(), s, pricing.priceFor(s)));
    }
    String getId() { return id; }
    Movie getMovie() { return movie; }
    Theatre getTheatre() { return theatre; }
    LocalDateTime getStartTime() { return startTime; }
    ShowSeat getShowSeat(String seatId) {
        ShowSeat ss = showSeats.get(seatId);
        if (ss == null) throw new IllegalArgumentException("Unknown seat: " + seatId);
        return ss;
    }
    Collection<ShowSeat> getAllShowSeats() { return showSeats.values(); }
}

/* ===================== Seat Locking ===================== */
class SeatLock {
    private final String userId;
    private final Instant lockedAt;
    private final Duration ttl;
    SeatLock(String userId, Instant lockedAt, Duration ttl) {
        this.userId = userId; this.lockedAt = lockedAt; this.ttl = ttl;
    }
    String getUserId() { return userId; }
    boolean isExpired() { return Instant.now().isAfter(lockedAt.plus(ttl)); }
}

interface SeatLockProvider {
    /** Atomically lock ALL seats for user, or fail without locking any (all-or-nothing). */
    void lockSeats(Show show, List<ShowSeat> seats, String userId);
    /** Release locks held by this user. */
    void unlockSeats(Show show, List<ShowSeat> seats, String userId);
    /** Atomically verify user still holds live locks, then transition seats to BOOKED. */
    void confirmSeats(Show show, List<ShowSeat> seats, String userId);
    /** Seat ids with a live (non-expired) lock. */
    Set<String> liveLockedSeatIds(Show show);
}

class InMemorySeatLockProvider implements SeatLockProvider {
    private final Duration ttl;
    // showId -> (seatId -> lock)
    private final Map<String, Map<String, SeatLock>> locks = new ConcurrentHashMap<>();
    // one monitor object per show -> seat operations on a show are serialized,
    // but DIFFERENT shows never block each other.
    private final Map<String, Object> monitors = new ConcurrentHashMap<>();

    InMemorySeatLockProvider(Duration ttl) { this.ttl = ttl; }

    private Object monitorFor(Show show) {
        return monitors.computeIfAbsent(show.getId(), k -> new Object());
    }
    private Map<String, SeatLock> locksFor(Show show) {
        return locks.computeIfAbsent(show.getId(), k -> new HashMap<>());
    }

    public void lockSeats(Show show, List<ShowSeat> seats, String userId) {
        synchronized (monitorFor(show)) {
            Map<String, SeatLock> showLocks = locksFor(show);
            // Phase 1 — validate everything BEFORE mutating (all-or-nothing).
            for (ShowSeat s : seats) {
                if (s.getStatus() == ShowSeatStatus.BOOKED)
                    throw new SeatUnavailableException("Already booked: " + s.getId());
                SeatLock l = showLocks.get(s.getId());
                if (l != null && !l.isExpired() && !l.getUserId().equals(userId))
                    throw new SeatUnavailableException("Locked by another user: " + s.getId());
            }
            // Phase 2 — commit.
            Instant now = Instant.now();
            for (ShowSeat s : seats)
                showLocks.put(s.getId(), new SeatLock(userId, now, ttl));
        }
    }

    public void unlockSeats(Show show, List<ShowSeat> seats, String userId) {
        synchronized (monitorFor(show)) {
            Map<String, SeatLock> showLocks = locksFor(show);
            for (ShowSeat s : seats) {
                SeatLock l = showLocks.get(s.getId());
                if (l != null && l.getUserId().equals(userId)) showLocks.remove(s.getId());
            }
        }
    }

    public void confirmSeats(Show show, List<ShowSeat> seats, String userId) {
        // Validation + BOOKED transition must be atomic so a lock cannot expire
        // and be re-acquired by someone else mid-confirmation.
        synchronized (monitorFor(show)) {
            Map<String, SeatLock> showLocks = locksFor(show);
            for (ShowSeat s : seats) {
                SeatLock l = showLocks.get(s.getId());
                if (l == null || l.isExpired() || !l.getUserId().equals(userId))
                    throw new SeatUnavailableException("Lock lost/expired: " + s.getId());
            }
            for (ShowSeat s : seats) {
                s.setStatus(ShowSeatStatus.BOOKED);
                showLocks.remove(s.getId());
            }
        }
    }

    public Set<String> liveLockedSeatIds(Show show) {
        synchronized (monitorFor(show)) {
            Set<String> out = new HashSet<>();
            for (Map.Entry<String, SeatLock> e : locksFor(show).entrySet())
                if (!e.getValue().isExpired()) out.add(e.getKey()); // lazy expiry
            return out;
        }
    }
}

/* ===================== Payment ===================== */
class PaymentDetails {
    private final String method;                  // "CARD", "UPI"
    private final Map<String, String> attributes;
    PaymentDetails(String method, Map<String, String> attributes) {
        this.method = method; this.attributes = attributes;
    }
    String getMethod() { return method; }
    Map<String, String> getAttributes() { return attributes; }
}

class Payment {
    private final String id; private final double amount; private final PaymentStatus status;
    Payment(String id, double amount, PaymentStatus status) {
        this.id = id; this.amount = amount; this.status = status;
    }
    PaymentStatus getStatus() { return status; }
}

interface PaymentStrategy { boolean pay(double amount, PaymentDetails d); }

class CardPaymentStrategy implements PaymentStrategy {
    public boolean pay(double amount, PaymentDetails d) {
        return d.getAttributes().containsKey("cardNumber"); // simulated PSP call
    }
}
class UpiPaymentStrategy implements PaymentStrategy {
    public boolean pay(double amount, PaymentDetails d) {
        return d.getAttributes().containsKey("vpa");
    }
}

class PaymentService {
    private final Map<String, PaymentStrategy> strategies;
    PaymentService(Map<String, PaymentStrategy> strategies) { this.strategies = strategies; }
    Payment process(double amount, PaymentDetails d) {
        PaymentStrategy s = strategies.get(d.getMethod());
        if (s == null) throw new IllegalArgumentException("Unsupported method: " + d.getMethod());
        boolean ok = s.pay(amount, d);
        return new Payment(UUID.randomUUID().toString(), amount,
                ok ? PaymentStatus.SUCCESS : PaymentStatus.FAILED);
    }
}

/* ===================== Booking ===================== */
class Booking {
    private final String id;
    private final User user;
    private final Show show;
    private final List<ShowSeat> seats;
    private final double amount;
    private volatile BookingStatus status = BookingStatus.CREATED;
    private volatile Payment payment;
    Booking(String id, User user, Show show, List<ShowSeat> seats, double amount) {
        this.id = id; this.user = user; this.show = show;
        this.seats = seats; this.amount = amount;
    }
    String getId() { return id; }
    User getUser() { return user; }
    Show getShow() { return show; }
    List<ShowSeat> getSeats() { return seats; }
    double getAmount() { return amount; }
    BookingStatus getStatus() { return status; }
    void setStatus(BookingStatus s) { this.status = s; }
    void setPayment(Payment p) { this.payment = p; }
}

class BookingService {
    private final SeatLockProvider lockProvider;
    private final PaymentService paymentService;
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();

    BookingService(SeatLockProvider lp, PaymentService ps) {
        this.lockProvider = lp; this.paymentService = ps;
    }

    /** Step 1: select seats. Locks them; user must pay within the lock TTL. */
    Booking createBooking(User user, Show show, List<String> seatIds) {
        if (seatIds == null || seatIds.isEmpty())
            throw new IllegalArgumentException("Select at least one seat");
        List<ShowSeat> seats = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        for (String id : seatIds) {
            if (!dedup.add(id)) throw new IllegalArgumentException("Duplicate seat: " + id);
            seats.add(show.getShowSeat(id));   // also validates seat exists
        }
        lockProvider.lockSeats(show, seats, user.getId()); // throws if any unavailable
        double amount = seats.stream().mapToDouble(ShowSeat::getPrice).sum();
        Booking b = new Booking(UUID.randomUUID().toString(), user, show, seats, amount);
        bookings.put(b.getId(), b);
        return b;
    }

    /** Step 2: pay & confirm. */
    Booking confirmBooking(String bookingId, PaymentDetails details) {
        Booking b = bookings.get(bookingId);
        if (b == null) throw new IllegalArgumentException("Unknown booking: " + bookingId);
        // Serialize confirm/cancel for THIS booking (does not block other bookings).
        synchronized (b) {
            if (b.getStatus() != BookingStatus.CREATED)
                throw new IllegalStateException("Booking is " + b.getStatus());
            // Payment is slow; we hold only this booking's monitor, not the show monitor.
            Payment payment = paymentService.process(b.getAmount(), details);
            if (payment.getStatus() != PaymentStatus.SUCCESS)
                // Keep seats locked: user can retry payment within the remaining TTL.
                throw new IllegalStateException("Payment failed for booking " + bookingId);
            try {
                lockProvider.confirmSeats(b.getShow(), b.getSeats(), b.getUser().getId());
            } catch (SeatUnavailableException e) {
                // Locks expired between payment and confirm -> must refund (see follow-ups).
                b.setStatus(BookingStatus.EXPIRED);
                throw new IllegalStateException("Locks expired; refund required", e);
            }
            b.setPayment(payment);
            b.setStatus(BookingStatus.CONFIRMED);
            return b;
        }
    }

    /** Cancel an unpaid booking and release its locks. */
    void cancelBooking(String bookingId) {
        Booking b = bookings.get(bookingId);
        if (b == null) return;
        synchronized (b) {
            if (b.getStatus() == BookingStatus.CREATED) {
                lockProvider.unlockSeats(b.getShow(), b.getSeats(), b.getUser().getId());
                b.setStatus(BookingStatus.CANCELLED);
            }
            // Cancelling a CONFIRMED booking (seat release + refund) -> Section 9.
        }
    }
}

/* ===================== Catalog / Search ===================== */
class MovieBookingService {
    private final Map<String, Movie> movies = new ConcurrentHashMap<>();
    private final Map<String, Theatre> theatres = new ConcurrentHashMap<>();
    private final Map<String, Show> shows = new ConcurrentHashMap<>();

    void addMovie(Movie m) { movies.put(m.getId(), m); }
    void addTheatre(Theatre t) { theatres.put(t.getId(), t); }
    void addShow(Show s) { shows.put(s.getId(), s); }

    List<Show> searchShows(String city, String movieId) {
        return shows.values().stream()
                .filter(s -> s.getTheatre().getCity().equalsIgnoreCase(city))
                .filter(s -> s.getMovie().getId().equals(movieId))
                .sorted(Comparator.comparing(Show::getStartTime))
                .collect(Collectors.toList());
    }

    /** Available = neither BOOKED nor live-locked. */
    List<ShowSeat> availableSeats(Show show, SeatLockProvider lockProvider) {
        Set<String> locked = lockProvider.liveLockedSeatIds(show);
        return show.getAllShowSeats().stream()
                .filter(ss -> ss.getStatus() == ShowSeatStatus.AVAILABLE)
                .filter(ss -> !locked.contains(ss.getId()))
                .collect(Collectors.toList());
    }
}

/* ===================== Demo ===================== */
class Demo {
    static void run() {
        Map<SeatType, Double> base = new EnumMap<>(SeatType.class);
        base.put(SeatType.REGULAR, 200.0);
        base.put(SeatType.PREMIUM, 350.0);
        base.put(SeatType.RECLINER, 500.0);
        PricingStrategy pricing = new DefaultPricingStrategy(base);

        List<Seat> seats = new ArrayList<>();
        for (int r = 0; r < 2; r++)
            for (int c = 0; c < 3; c++)
                seats.add(new Seat("R" + r + "C" + c, r, c,
                        r == 0 ? SeatType.PREMIUM : SeatType.REGULAR));
        Screen screen = new Screen("SCR1", "Audi-1", seats);
        Theatre theatre = new Theatre("TH1", "PVR Forum", "Bangalore", List.of(screen));
        Movie movie = new Movie("M1", "Inception", "English", 148);
        Show show = new Show("SH1", movie, theatre, screen,
                LocalDateTime.of(2026, 6, 1, 18, 30), pricing);

        SeatLockProvider lockProvider = new InMemorySeatLockProvider(Duration.ofMinutes(5));
        Map<String, PaymentStrategy> ps = new HashMap<>();
        ps.put("CARD", new CardPaymentStrategy());
        ps.put("UPI", new UpiPaymentStrategy());
        BookingService bookingService = new BookingService(lockProvider, new PaymentService(ps));

        MovieBookingService catalog = new MovieBookingService();
        catalog.addMovie(movie); catalog.addTheatre(theatre); catalog.addShow(show);

        User alice = new User("U1", "Alice");
        User bob = new User("U2", "Bob");

        System.out.println("Shows found: " + catalog.searchShows("Bangalore", "M1").size());
        System.out.println("Available seats: " + catalog.availableSeats(show, lockProvider).size());

        Booking ab = bookingService.createBooking(alice, show, List.of("R0C0", "R0C1"));
        System.out.println("Alice booking " + ab.getId() + " amount=" + ab.getAmount());

        try {
            bookingService.createBooking(bob, show, List.of("R0C1", "R0C2"));
        } catch (SeatUnavailableException e) {
            System.out.println("Bob blocked as expected: " + e.getMessage());
        }

        Booking confirmed = bookingService.confirmBooking(ab.getId(),
                new PaymentDetails("CARD", Map.of("cardNumber", "4111111111111111")));
        System.out.println("Alice booking status: " + confirmed.getStatus());

        Booking bb = bookingService.createBooking(bob, show, List.of("R0C2"));
        System.out.println("Bob booking " + bb.getId() + " status=" + bb.getStatus());
        System.out.println("Available seats now: " + catalog.availableSeats(show, lockProvider).size());

        // Concurrency test: 4 threads race for one free seat -> exactly 1 wins.
        ExecutorService ex = Executors.newFixedThreadPool(4);
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int id = i;
            fs.add(ex.submit(() -> {
                try {
                    bookingService.createBooking(new User("T" + id, "T" + id),
                            show, List.of("R1C0"));
                    wins.incrementAndGet();
                } catch (SeatUnavailableException ignored) { }
            }));
        }
        for (Future<?> f : fs) { try { f.get(); } catch (Exception ignored) { } }
        ex.shutdown();
        System.out.println("Concurrent winners for R1C0 (must be 1): " + wins.get());
    }
}