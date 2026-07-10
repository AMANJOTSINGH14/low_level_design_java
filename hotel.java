import java.util.*;
import java.time.LocalDate;

enum RoomType { STANDARD, DELUXE, SUITE }
enum BookingStatus { CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED }

class Room {
    String id; RoomType type; double pricePerNight;
    Room(String id, RoomType type, double price) {
        this.id = id; this.type = type; this.pricePerNight = price;
    }
}

class Guest {
    String id, name, email;
    Guest(String id, String name, String email) { this.id = id; this.name = name; this.email = email; }
}

// ===== FACTORY PATTERN =====
// Centralizes room creation so price/amenities per type live in one place
// instead of being decided ad-hoc wherever `new Room(...)` gets called.
class RoomFactory {
    static Room createRoom(String id, RoomType type) {
        switch (type) {
            case STANDARD: return new Room(id, RoomType.STANDARD, 80.0);
            case DELUXE:   return new Room(id, RoomType.DELUXE, 150.0);
            case SUITE:    return new Room(id, RoomType.SUITE, 300.0);
            default: throw new IllegalArgumentException("Unknown room type: " + type);
        }
    }
}

// ===== STATE PATTERN =====
// Each concrete state encapsulates the legal transitions out of that state.
// Booking delegates advance()/cancel() to whatever state it's currently in.
interface BookingState {
    void next(Booking booking);
    void cancel(Booking booking);
}

class ConfirmedState implements BookingState {
    public void next(Booking b) { b.status = BookingStatus.CHECKED_IN; b.setState(new CheckedInState()); }
    public void cancel(Booking b) { b.status = BookingStatus.CANCELLED; b.setState(new CancelledState()); }
}
class CheckedInState implements BookingState {
    public void next(Booking b) { b.status = BookingStatus.CHECKED_OUT; b.setState(new CheckedOutState()); }
    public void cancel(Booking b) { throw new RuntimeException("Cannot cancel after check-in"); }
}
class CheckedOutState implements BookingState {
    public void next(Booking b) { throw new RuntimeException("Already checked out"); }
    public void cancel(Booking b) { throw new RuntimeException("Already checked out"); }
}
class CancelledState implements BookingState {
    public void next(Booking b) { throw new RuntimeException("Booking cancelled"); }
    public void cancel(Booking b) { throw new RuntimeException("Already cancelled"); }
}

class Booking {
    String id; Guest guest; Room room;
    LocalDate checkIn, checkOut;
    BookingStatus status;
    BookingState state;

    Booking(String id, Guest guest, Room room, LocalDate in_, LocalDate out) {
        this.id = id; this.guest = guest; this.room = room;
        this.checkIn = in_; this.checkOut = out;
        this.status = BookingStatus.CONFIRMED;
        this.state = new ConfirmedState();
    }
    void setState(BookingState s) { this.state = s; }
    void advance() { state.next(this); }
    void cancel() { state.cancel(this); }
    double totalCost() {
        long nights = java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut);
        return nights * room.pricePerNight;
    }
}

// ===== STRATEGY PATTERN =====
// PaymentStrategy is the interchangeable algorithm. Hotel.book() takes a
// PaymentStrategy parameter, so callers can swap in a different payment
// method without touching Hotel or Booking.
interface PaymentStrategy { boolean pay(double amount); }
class CardPayment implements PaymentStrategy {
    String cardNumber;
    CardPayment(String card) { this.cardNumber = card; }
    public boolean pay(double amount) {
        System.out.println("Charged " + amount + " to card " + cardNumber);
        return true;
    }
}

// ===== OBSERVER PATTERN =====
// Observer is the subscriber interface. EmailObserver is a concrete
// implementation that actually provides a body for notify() — this is
// what runs when Hotel.notifyAll() iterates the observer list.
interface Observer { void notify(String message); }

class EmailObserver implements Observer {
    String email;
    EmailObserver(String email) { this.email = email; }
    public void notify(String message) {
        System.out.println("Email to " + email + ": " + message);
    }
}

class SmsObserver implements Observer {
    String phone;
    SmsObserver(String phone) { this.phone = phone; }
    public void notify(String message) {
        System.out.println("SMS to " + phone + ": " + message);
    }
}

class Hotel {
    String name;
    List<Room> rooms = new ArrayList<>();
    List<Booking> bookings = new ArrayList<>();
    List<Observer> observers = new ArrayList<>();

    Hotel(String name) { this.name = name; }
    void addRoom(Room r) { rooms.add(r); }
    void addObserver(Observer o) { observers.add(o); }
    void notifyAll(String msg) { observers.forEach(o -> o.notify(msg)); }

    List<Room> searchAvailable(RoomType type, LocalDate checkIn, LocalDate checkOut) {
        Set<String> booked = new HashSet<>();
        for (Booking b : bookings) {
            if (b.status == BookingStatus.CANCELLED || b.status == BookingStatus.CHECKED_OUT) continue;
            if (b.checkIn.isBefore(checkOut) && b.checkOut.isAfter(checkIn))
                booked.add(b.room.id);
        }
        List<Room> avail = new ArrayList<>();
        for (Room r : rooms)
            if (r.type == type && !booked.contains(r.id)) avail.add(r);
        return avail;
    }

    Booking book(Guest guest, Room room, LocalDate in_, LocalDate out, PaymentStrategy payment) {
        Booking b = new Booking(UUID.randomUUID().toString().substring(0, 8), guest, room, in_, out);
        if (payment.pay(b.totalCost())) {
            bookings.add(b);
            notifyAll("Booking confirmed for " + guest.name); // now reaches real observers
            return b;
        }
        return null;
    }
}

// Usage:
// Hotel h = new Hotel("Grand");
// h.addRoom(RoomFactory.createRoom("101", RoomType.DELUXE));
//
// Guest g = new Guest("G1", "Alice", "alice@mail.com");
// h.addObserver(new EmailObserver(g.email));   // <-- addObserver actually called
// h.addObserver(new SmsObserver("555-0100"));  // <-- can register multiple observers
//
// List<Room> avail = h.searchAvailable(RoomType.DELUXE, LocalDate.of(2025,1,1), LocalDate.of(2025,1,3));
// Booking b = h.book(g, avail.get(0), LocalDate.of(2025,1,1), LocalDate.of(2025,1,3), new CardPayment("4111"));
// // book() -> notifyAll() -> loops over [EmailObserver, SmsObserver] -> each .notify() body actually runs
// // prints:
// // Email to alice@mail.com: Booking confirmed for Alice
// // SMS to 555-0100: Booking confirmed for Alice
//
// b.advance(); // check in
// b.advance(); // check out
