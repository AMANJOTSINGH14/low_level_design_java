import java.time.LocalDateTime;
import java.util.*;

// Enums keep the "kinds" of things closed and type-safe instead of using raw
// strings/ints scattered through the code — also gives us ordinal() for the
// compact/regular/large size comparison used in ParkingSpot.canFit.
enum VehicleType { CAR, TRUCK, MOTORCYCLE, VAN }
enum SpotType { COMPACT, REGULAR, LARGE }   // declared smallest -> largest on purpose, ordinal() relies on this order
enum PaymentMethod { CASH, CARD, UPI }

// Base class for anything that can be parked. Abstract because "a vehicle" alone
// is never instantiated — each concrete type must say what spot size it needs.
abstract class Vehicle {
    String licensePlate;
    VehicleType type;

    Vehicle(String plate, VehicleType type) {
        this.licensePlate = plate;
        this.type = type;
    }

    // Template-method-ish hook: subclasses declare their own sizing requirement,
    // so ParkingFloor/ParkingSpot never need a switch statement on VehicleType.
    abstract SpotType requiredSpotType();
}

class Car extends Vehicle {
    Car(String plate) { super(plate, VehicleType.CAR); }
    SpotType requiredSpotType() { return SpotType.REGULAR; }
}

class Truck extends Vehicle {
    Truck(String plate) { super(plate, VehicleType.TRUCK); }
    SpotType requiredSpotType() { return SpotType.LARGE; }
}

class Motorcycle extends Vehicle {
    Motorcycle(String plate) { super(plate, VehicleType.MOTORCYCLE); }
    SpotType requiredSpotType() { return SpotType.COMPACT; }
}

// Represents one physical parking space. Holds a reference to the currently
// parked vehicle (null = empty) rather than a boolean flag, so we always know
// *what's* parked there, not just *that* something is.
class ParkingSpot {
    String id;
    SpotType type;
    Vehicle vehicle;

    ParkingSpot(String id, SpotType type) {
        this.id = id;
        this.type = type;
    }

    boolean isAvailable() { return vehicle == null; }

    // A vehicle fits if the spot is free AND the spot is at least as big as what
    // the vehicle needs. Comparing ordinal() works because the enum is declared
    // COMPACT < REGULAR < LARGE, so a motorcycle (COMPACT) can fit in a LARGE spot,
    // but a truck (LARGE) can't fit in a REGULAR one.
    boolean canFit(Vehicle v) {
        return isAvailable() && v.requiredSpotType().ordinal() <= type.ordinal();
    }

    void park(Vehicle v) { this.vehicle = v; }
    void free() { this.vehicle = null; }
}

// Groups spots by physical floor. Keeps spot-finding logic scoped per floor so
// ParkingLot can just ask each floor in turn instead of scanning one giant list.
class ParkingFloor {
    String id;
    List<ParkingSpot> spots = new ArrayList<>();

    ParkingFloor(String id) { this.id = id; }

    void addSpot(ParkingSpot spot) { spots.add(spot); }

    // First-fit search: returns the first spot on this floor that can take the
    // vehicle, or null if none. Simple and fine for a moderate number of spots;
    // would want an index (e.g. by SpotType) if this were scanning thousands.
    ParkingSpot findSpot(Vehicle v) {
        return spots.stream().filter(s -> s.canFit(v)).findFirst().orElse(null);
    }
}

// Represents one parking session. Generated once on entry and looked up again
// on exit to compute duration/fee — effectively the "receipt" tying a vehicle
// to a spot and a timestamp.
class Ticket {
    String id;
    Vehicle vehicle;
    ParkingSpot spot;
    LocalDateTime entryTime;

    Ticket(Vehicle v, ParkingSpot s) {
        // Short random id instead of a full UUID string — good enough for a ticket
        // number, easier to read/print than a full 36-char UUID.
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.vehicle = v;
        this.spot = s;
        this.entryTime = LocalDateTime.now();
    }
}

// Strategy pattern: decouples "how a fee is calculated" from ParkingLot itself,
// so pricing models (hourly, flat-rate, per-minute, weekday/weekend) can be
// swapped in without touching entry/exit logic.
interface PaymentStrategy {
    double calculate(Ticket ticket);
}

class HourlyPayment implements PaymentStrategy {
    double rate;

    HourlyPayment(double rate) { this.rate = rate; }

    public double calculate(Ticket ticket) {
        // +1 rounds any partial hour up to a full billable hour (standard garage
        // pricing behavior — 10 minutes parked still costs 1 hour).
        long hours = java.time.Duration.between(ticket.entryTime, LocalDateTime.now()).toHours() + 1;
        return hours * rate;
    }
}

// The main facade coordinating floors, tickets, and payment. Singleton because
// a real-world parking lot is a single physical resource — it doesn't make sense
// to have multiple independent ParkingLot instances managing the same spots.
class ParkingLot {
    private static ParkingLot instance;

    String name;
    List<ParkingFloor> floors = new ArrayList<>();
    Map<String, Ticket> activeTickets = new HashMap<>(); // ticketId -> Ticket, for O(1) lookup on exit
    PaymentStrategy paymentStrategy;

    // Private constructor enforces the singleton — the only way in is getInstance().
    private ParkingLot(String name, PaymentStrategy ps) {
        this.name = name;
        this.paymentStrategy = ps;
    }

    // synchronized guards against two threads both seeing instance == null and
    // constructing two separate lots in a race condition.
    static synchronized ParkingLot getInstance(String name, PaymentStrategy ps) {
        if (instance == null) instance = new ParkingLot(name, ps);
        return instance;
    }

    void addFloor(ParkingFloor floor) { floors.add(floor); }

    // Walks floors in order, asking each for a spot until one is found. Parks the
    // vehicle immediately on the first hit so nobody else can grab that spot
    // between finding it and using it (single-threaded assumption here — a
    // concurrent version would need locking per spot or floor).
    Ticket entry(Vehicle vehicle) {
        for (ParkingFloor floor : floors) {
            ParkingSpot spot = floor.findSpot(vehicle);
            if (spot != null) {
                spot.park(vehicle);
                Ticket ticket = new Ticket(vehicle, spot);
                activeTickets.put(ticket.id, ticket);
                return ticket;
            }
        }
        return null; // no floor had a suitable free spot — lot is full for this vehicle type
    }

    // Removes the ticket (so it can't be reused), computes the fee via whatever
    // strategy the lot was configured with, and frees the spot for the next car.
    double exit(String ticketId) {
        Ticket ticket = activeTickets.remove(ticketId);
        if (ticket == null) throw new RuntimeException("Invalid ticket");
        double fee = paymentStrategy.calculate(ticket);
        ticket.spot.free();
        return fee;
    }

    // Flattens every floor's spot list into one stream and counts free ones —
    // simple aggregate query, O(total spots) each call.
    int availableSpots() {
        return (int) floors.stream().flatMap(f -> f.spots.stream()).filter(ParkingSpot::isAvailable).count();
    }
}

// Usage:
// ParkingLot lot = ParkingLot.getInstance("MainLot", new HourlyPayment(2.0));
// ParkingFloor f1 = new ParkingFloor("F1");
// f1.addSpot(new ParkingSpot("S1", SpotType.REGULAR));
// f1.addSpot(new ParkingSpot("S2", SpotType.LARGE));
// lot.addFloor(f1);
// Ticket t = lot.entry(new Car("ABC-123"));
// double fee = lot.exit(t.id);