import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

enum Direction { UP, DOWN, IDLE }
enum ElevatorState { IDLE, MOVING, DOORS_OPEN, MAINTENANCE }

/* ---------- Observer ---------- */
interface ElevatorObserver {
    void onUpdate(int elevatorId, int floor, Direction dir, ElevatorState state);
}

class ConsoleDisplay implements ElevatorObserver {
    public void onUpdate(int id, int floor, Direction dir, ElevatorState state) {
        System.out.printf("  [Display] Elevator %d -> floor %d, dir %s, %s%n",
                id, floor, dir, state);
    }
}

/* ---------- Elevator ---------- */
class Elevator {
    private final int id;
    private final int minFloor, maxFloor;
    private int currentFloor;
    private Direction direction = Direction.IDLE;
    private ElevatorState state = ElevatorState.IDLE;

    // LOOK algorithm: pending stops split by the direction they will be served in.
    private final TreeSet<Integer> upStops = new TreeSet<>();
    private final TreeSet<Integer> downStops = new TreeSet<>();

    private final ReentrantLock lock = new ReentrantLock(); // calls arrive concurrently
    private final List<ElevatorObserver> observers = new ArrayList<>();

    Elevator(int id, int minFloor, int maxFloor, int startFloor) {
        this.id = id; this.minFloor = minFloor;
        this.maxFloor = maxFloor; this.currentFloor = startFloor;
    }

    void addObserver(ElevatorObserver o) { observers.add(o); }

    int getId()                 { return id; }
    int getCurrentFloor()       { lock.lock(); try { return currentFloor; } finally { lock.unlock(); } }
    Direction getDirection()    { lock.lock(); try { return direction;    } finally { lock.unlock(); } }
    ElevatorState getState()    { lock.lock(); try { return state;        } finally { lock.unlock(); } }
    boolean isOperational()     { return getState() != ElevatorState.MAINTENANCE; }

    /* Hall call: a person at `floor` wants to travel `reqDir`. */
    void addHallStop(int floor, Direction reqDir) {
        lock.lock();
        try {
            if (floor < minFloor || floor > maxFloor) return;
            if (reqDir == Direction.UP) upStops.add(floor);
            else                        downStops.add(floor);
            wakeIfIdle();
        } finally { lock.unlock(); }
    }

    /* Car call: a passenger inside the cabin selects a destination. */
    void addCarStop(int floor) {
        lock.lock();
        try {
            if (floor < minFloor || floor > maxFloor || floor == currentFloor) return;
            if (floor > currentFloor) upStops.add(floor);
            else                      downStops.add(floor);
            wakeIfIdle();
        } finally { lock.unlock(); }
    }

    /* Heuristic cost for the dispatcher: lower is better. O(1). */
    int estimateCost(int floor, Direction reqDir) {
        lock.lock();
        try {
            int distance = Math.abs(currentFloor - floor);
            if (direction == Direction.IDLE) return distance;
            boolean movingTowards =
                    (direction == Direction.UP   && floor >= currentFloor) ||
                    (direction == Direction.DOWN && floor <= currentFloor);
            // Best case: cabin heads toward the floor in the direction the
            // passenger wants -> it picks them up en route.
            if (movingTowards && direction == reqDir) return distance;
            // Otherwise the cabin must finish its current sweep first: penalise.
            return distance + 2 * (maxFloor - minFloor);
        } finally { lock.unlock(); }
    }

    /* Advance one tick. A production system runs this on a timer thread;
       the demo calls it deterministically. */
    void step() {
        lock.lock();
        try {
            if (state == ElevatorState.MAINTENANCE) return;
            if (state == ElevatorState.DOORS_OPEN) state = ElevatorState.IDLE; // doors close

            Integer target = peekTarget();
            if (target == null) { direction = Direction.IDLE; state = ElevatorState.IDLE; return; }

            if (target == currentFloor) {                 // arrived -> serve floor
                upStops.remove(currentFloor);
                downStops.remove(currentFloor);
                state = ElevatorState.DOORS_OPEN;
                Integer next = peekTarget();
                if (next == null)               direction = Direction.IDLE;
                else if (next > currentFloor)   direction = Direction.UP;
                else if (next < currentFloor)   direction = Direction.DOWN;
            } else {                                      // move one floor toward target
                direction = target > currentFloor ? Direction.UP : Direction.DOWN;
                currentFloor += (direction == Direction.UP) ? 1 : -1;
                state = ElevatorState.MOVING;
            }
            notifyObservers();
        } finally { lock.unlock(); }
    }

    /* --- helpers (caller holds lock) --- */

    private void wakeIfIdle() {
        if (direction == Direction.IDLE) {
            Integer t = peekTarget();
            if (t != null) direction = (t >= currentFloor) ? Direction.UP : Direction.DOWN;
        }
    }

    /* LOOK: prefer stops in the current sweep direction; only then reverse. */
    private Integer peekTarget() {
        if (direction == Direction.UP) {
            Integer c = upStops.ceiling(currentFloor);
            if (c != null) return c;
            if (!downStops.isEmpty()) return downStops.last(); // reverse to highest down stop
            if (!upStops.isEmpty())   return upStops.first();  // up calls left below us
            return null;
        }
        if (direction == Direction.DOWN) {
            Integer f = downStops.floor(currentFloor);
            if (f != null) return f;
            if (!upStops.isEmpty())   return upStops.first();
            if (!downStops.isEmpty()) return downStops.last();
            return null;
        }
        // IDLE: head to the nearest pending stop of any kind.
        Integer best = null; int bestDist = Integer.MAX_VALUE;
        for (int f : upStops)   { int d = Math.abs(f - currentFloor); if (d < bestDist) { bestDist = d; best = f; } }
        for (int f : downStops) { int d = Math.abs(f - currentFloor); if (d < bestDist) { bestDist = d; best = f; } }
        return best;
    }

    private void notifyObservers() {
        for (ElevatorObserver o : observers) o.onUpdate(id, currentFloor, direction, state);
    }
}

/* ---------- Dispatch strategy (Strategy pattern) ---------- */
interface DispatchStrategy {
    Elevator selectElevator(List<Elevator> elevators, int floor, Direction dir);
}

class NearestCarStrategy implements DispatchStrategy {
    public Elevator selectElevator(List<Elevator> elevators, int floor, Direction dir) {
        Elevator best = null; int bestCost = Integer.MAX_VALUE;
        for (Elevator e : elevators) {
            if (!e.isOperational()) continue;
            int cost = e.estimateCost(floor, dir);
            if (cost < bestCost) { bestCost = cost; best = e; }
        }
        return best;
    }
}

/* ---------- Facade ---------- */
class ElevatorSystem {
    private final List<Elevator> elevators;
    private DispatchStrategy strategy;

    ElevatorSystem(List<Elevator> elevators, DispatchStrategy strategy) {
        this.elevators = elevators; this.strategy = strategy;
    }
    void setStrategy(DispatchStrategy s) { this.strategy = s; }
    List<Elevator> getElevators()        { return elevators; }

    Elevator requestElevator(int floor, Direction dir) {        // external hall call
        Elevator chosen = strategy.selectElevator(elevators, floor, dir);
        if (chosen == null) { System.out.println("  No elevator available."); return null; }
        chosen.addHallStop(floor, dir);
        System.out.printf("  Hall call floor %d (%s) -> Elevator %d%n", floor, dir, chosen.getId());
        return chosen;
    }
    void selectFloor(Elevator e, int destination) {             // internal car call
        e.addCarStop(destination);
        System.out.printf("  Car call: Elevator %d -> floor %d%n", e.getId(), destination);
    }
    void step() { for (Elevator e : elevators) e.step(); }
}

/* ---------- Demo ---------- */
public class ElevatorDemo {
    public static void main(String[] args) {
        ConsoleDisplay display = new ConsoleDisplay();
        Elevator e1 = new Elevator(1, 0, 10, 0);
        Elevator e2 = new Elevator(2, 0, 10, 8);
        e1.addObserver(display); e2.addObserver(display);

        ElevatorSystem system = new ElevatorSystem(
                new ArrayList<>(List.of(e1, e2)), new NearestCarStrategy());

        Elevator a = system.requestElevator(3, Direction.UP);   // -> E1 (idle at 0)
        system.requestElevator(9, Direction.DOWN);              // -> E2 (idle at 8)

        boolean carCallMade = false;
        for (int tick = 1; tick <= 20; tick++) {
            System.out.println("Tick " + tick + ":");
            system.step();
            // Passenger boards E1 at floor 3 and presses 7.
            if (!carCallMade && a.getCurrentFloor() == 3
                    && a.getState() == ElevatorState.DOORS_OPEN) {
                system.selectFloor(a, 7);
                carCallMade = true;
            }
            if (allIdle(system)) { System.out.println("All elevators idle. Done."); break; }
        }
    }
    private static boolean allIdle(ElevatorSystem s) {
        for (Elevator e : s.getElevators())
            if (e.getState() != ElevatorState.IDLE) return false;
        return true;
    }
}