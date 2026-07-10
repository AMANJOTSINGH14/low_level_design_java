import java.util.*;
import java.time.LocalDate;

enum VehicleType { SEDAN, SUV, VAN, TRUCK }

abstract class Vehicle {
    String id, make, model; VehicleType type; double dailyRate;
    VehicleState state; // STATE PATTERN: holds current state object, not an enum
    Vehicle(String id, VehicleType type, String make, String model, double rate) {
        this.id = id; this.type = type; this.make = make; this.model = model;
        this.dailyRate = rate; this.state = new AvailableState();
    }
    void setState(VehicleState s) { this.state = s; } // called by state classes themselves, not by callers
}
class Sedan extends Vehicle { Sedan(String id, String make, String model, double rate) { super(id, VehicleType.SEDAN, make, model, rate); } }
class SUV extends Vehicle { SUV(String id, String make, String model, double rate) { super(id, VehicleType.SUV, make, model, rate); } }
class Van extends Vehicle { Van(String id, String make, String model, double rate) { super(id, VehicleType.VAN, make, model, rate); } }
class Truck extends Vehicle { Truck(String id, String make, String model, double rate) { super(id, VehicleType.TRUCK, make, model, rate); } }

// ===== FACTORY PATTERN =====
// Single place that knows how to build each Vehicle subtype.
// Called from usage code below: VehicleFactory.create(...)
class VehicleFactory {
    static Vehicle create(String id, VehicleType type, String make, String model, double rate) {
        switch (type) {
            case SEDAN: return new Sedan(id, make, model, rate);
            case SUV:   return new SUV(id, make, model, rate);
            case VAN:   return new Van(id, make, model, rate);
            case TRUCK: return new Truck(id, make, model, rate);
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }
}

// ===== STATE PATTERN =====
// Each state class owns its own legal transitions. Illegal calls throw
// instead of silently overwriting a field. Vehicle.state.X(v) is how
// CarRentalSystem triggers transitions further down.
interface VehicleState {
    void reserve(Vehicle v);
    void pickup(Vehicle v);
    void returnVehicle(Vehicle v);
    void sendToMaintenance(Vehicle v);
}
class AvailableState implements VehicleState {
    public void reserve(Vehicle v) { v.setState(new ReservedState()); } // Available -> Reserved
    public void pickup(Vehicle v) { throw new RuntimeException("Must reserve before pickup"); }
    public void returnVehicle(Vehicle v) { throw new RuntimeException("Not rented"); }
    public void sendToMaintenance(Vehicle v) { v.setState(new MaintenanceState()); } // Available -> Maintenance
}
class ReservedState implements VehicleState {
    public void reserve(Vehicle v) { throw new RuntimeException("Already reserved"); }
    public void pickup(Vehicle v) { v.setState(new RentedState()); } // Reserved -> Rented
    public void returnVehicle(Vehicle v) { throw new RuntimeException("Not picked up yet"); }
    public void sendToMaintenance(Vehicle v) { throw new RuntimeException("Cannot service a reserved vehicle"); }
}
class RentedState implements VehicleState {
    public void reserve(Vehicle v) { throw new RuntimeException("Vehicle is rented"); }
    public void pickup(Vehicle v) { throw new RuntimeException("Already picked up"); }
    public void returnVehicle(Vehicle v) { v.setState(new AvailableState()); } // Rented -> Available
    public void sendToMaintenance(Vehicle v) { throw new RuntimeException("Return vehicle first"); }
}
class MaintenanceState implements VehicleState {
    public void reserve(Vehicle v) { throw new RuntimeException("Vehicle in maintenance"); }
    public void pickup(Vehicle v) { throw new RuntimeException("Vehicle in maintenance"); }
    public void returnVehicle(Vehicle v) { throw new RuntimeException("Vehicle in maintenance"); }
    public void sendToMaintenance(Vehicle v) { v.setState(new AvailableState()); } // Maintenance -> Available (serviced)
}

class Customer {
    String id, name, license;
    Customer(String id, String name, String license) { this.id = id; this.name = name; this.license = license; }
}

// ===== STRATEGY PATTERN =====
// Interchangeable pricing algorithm, injected into CarRentalSystem's
// constructor and invoked inside returnVehicle() below.
interface PricingStrategy { double calculate(Vehicle v, long days); }
class StandardPricing implements PricingStrategy {
    public double calculate(Vehicle v, long days) { return v.dailyRate * days; }
}
class WeeklyPricing implements PricingStrategy {
    public double calculate(Vehicle v, long days) {
        long weeks = days / 7, rem = days % 7;
        return weeks * v.dailyRate * 5 + rem * v.dailyRate;
    }
}

// ===== OBSERVER PATTERN =====
// Observer is the subscriber interface. EmailObserver is a concrete
// implementation whose notify() body actually runs when
// CarRentalSystem.notifyAll() loops over registered observers.
interface Observer { void notify(String message); }
class EmailObserver implements Observer {
    String email;
    EmailObserver(String email) { this.email = email; }
    public void notify(String message) { System.out.println("Email to " + email + ": " + message); }
}

class Insurance {
    String planName; double dailyCost;
    Insurance(String planName, double dailyCost) { this.planName = planName; this.dailyCost = dailyCost; }
}

class Payment {
    double amount; boolean paid;
    Payment(double amount) { this.amount = amount; }
    boolean process() { paid = true; System.out.println("Charged $" + amount); return paid; } // called from returnVehicle()
}

class RentalAgreement {
    String id; Vehicle vehicle; Customer customer; Insurance insurance; double deposit;
    RentalAgreement(String id, Vehicle v, Customer c, Insurance ins, double deposit) {
        this.id = id; this.vehicle = v; this.customer = c; this.insurance = ins; this.deposit = deposit;
    }
}

class Reservation {
    String id; Customer customer; VehicleType requestedType; LocalDate pickup, returnDate;
    Vehicle assigned;
    Reservation(String id, Customer c, VehicleType type, LocalDate pickup, LocalDate ret) {
        this.id = id; this.customer = c; this.requestedType = type;
        this.pickup = pickup; this.returnDate = ret;
    }
}

class Branch {
    String name, location; List<Vehicle> fleet = new ArrayList<>();
    Branch(String name, String loc) { this.name = name; this.location = loc; }
    void addVehicle(Vehicle v) { fleet.add(v); }
    Vehicle findAvailable(VehicleType type) {
        // checks against the state OBJECT now, not an enum value
        return fleet.stream().filter(v -> v.type == type && v.state instanceof AvailableState).findFirst().orElse(null);
    }
}

class CarRentalSystem {
    List<Branch> branches = new ArrayList<>();
    Map<String, Reservation> reservations = new HashMap<>();
    Map<String, RentalAgreement> agreements = new HashMap<>();
    List<Observer> observers = new ArrayList<>(); // OBSERVER PATTERN: subject holds its subscriber list
    PricingStrategy pricing; // STRATEGY PATTERN: subject holds the algorithm object

    CarRentalSystem(PricingStrategy pricing) { this.pricing = pricing; } // STRATEGY PATTERN: injected at construction
    void addBranch(Branch b) { branches.add(b); }
    void addObserver(Observer o) { observers.add(o); } // OBSERVER PATTERN: registration, called in usage below
    void notifyAll(String msg) { observers.forEach(o -> o.notify(msg)); } // OBSERVER PATTERN: broadcast, called twice below

    Reservation reserve(Customer c, VehicleType type, LocalDate pickup, LocalDate ret, Branch branch) {
        Vehicle v = branch.findAvailable(type);
        if (v == null) return null;
        v.state.reserve(v); // STATE PATTERN: delegates to AvailableState.reserve()
        Reservation r = new Reservation(UUID.randomUUID().toString().substring(0, 8), c, type, pickup, ret);
        r.assigned = v;
        reservations.put(r.id, r);
        notifyAll("Reservation confirmed for " + c.name); // OBSERVER PATTERN: fires on reservation
        return r;
    }

    RentalAgreement pickup(String resId, Insurance insurance, double deposit) {
        Reservation r = reservations.get(resId);
        r.assigned.state.pickup(r.assigned); // STATE PATTERN: delegates to ReservedState.pickup()
        RentalAgreement agreement = new RentalAgreement(UUID.randomUUID().toString().substring(0, 8),
                r.assigned, r.customer, insurance, deposit); // RentalAgreement actually created here, with insurance + deposit
        agreements.put(agreement.id, agreement);
        return agreement;
    }

    double returnVehicle(String resId) {
        Reservation r = reservations.remove(resId);
        long days = java.time.temporal.ChronoUnit.DAYS.between(r.pickup, r.returnDate);
        r.assigned.state.returnVehicle(r.assigned); // STATE PATTERN: delegates to RentedState.returnVehicle()
        double fee = pricing.calculate(r.assigned, Math.max(1, days)); // STRATEGY PATTERN: algorithm invoked here
        new Payment(fee).process(); // Payment actually created and processed here
        notifyAll("Vehicle returned by " + r.customer.name); // OBSERVER PATTERN: fires on return
        return fee;
    }
}

// Usage:
// CarRentalSystem sys = new CarRentalSystem(new StandardPricing()); // STRATEGY PATTERN: chosen here
// Branch b = new Branch("Downtown", "NYC");
// b.addVehicle(VehicleFactory.create("V1", VehicleType.SEDAN, "Toyota", "Camry", 50)); // FACTORY PATTERN: called here
// sys.addBranch(b);
// Customer c = new Customer("C1", "Alice", "DL-123");
// sys.addObserver(new EmailObserver("alice@mail.com")); // OBSERVER PATTERN: registered here
// Reservation r = sys.reserve(c, VehicleType.SEDAN, LocalDate.now(), LocalDate.now().plusDays(3), b);
// // -> triggers notifyAll -> EmailObserver.notify() actually runs -> prints "Email to alice@mail.com: Reservation confirmed for Alice"
// RentalAgreement agreement = sys.pickup(r.id, new Insurance("Basic", 5.0), 200.0);
// double fee = sys.returnVehicle(r.id);
// // -> triggers notifyAll again -> prints "Email to alice@mail.com: Vehicle returned by Alice"
