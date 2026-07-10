import java.util.*;
import java.time.LocalDateTime;

enum TableStatus { AVAILABLE, RESERVED, OCCUPIED }
enum OrderStatus { PLACED, IN_PREPARATION, READY, SERVED, COMPLETED }

class MenuItem {
    String name; double price; String category;
    MenuItem(String name, double price, String category) { this.name = name; this.price = price; this.category = category; }
}

class OrderItem {
    MenuItem item; int quantity; String notes;
    OrderItem(MenuItem item, int qty, String notes) { this.item = item; this.quantity = qty; this.notes = notes; }
    double subtotal() { return item.price * quantity; }
}

class Waiter {
    String id, name;
    Waiter(String id, String name) { this.id = id; this.name = name; }
}

class Chef {
    String id, name;
    Chef(String id, String name) { this.id = id; this.name = name; }
}

class Table {
    int number, capacity; TableStatus status;
    Waiter assignedWaiter; // was missing — step 1 requires this
    Table(int num, int cap) { number = num; capacity = cap; status = TableStatus.AVAILABLE; }
}

// Reservation was in "Key Classes" but never defined — links a guest to a
// future table/time, separate from immediate walk-in seating.
class Reservation {
    String guestName; Table table; LocalDateTime dateTime;
    Reservation(String guestName, Table table, LocalDateTime dateTime) {
        this.guestName = guestName; this.table = table; this.dateTime = dateTime;
    }
}

interface OrderCommand { void execute(); void undo(); }
class AddItemCommand implements OrderCommand {
    Order order; OrderItem item;
    AddItemCommand(Order o, OrderItem i) { order = o; item = i; }
    public void execute() { order.items.add(item); }
    public void undo() { order.items.remove(item); }
}
class RemoveItemCommand implements OrderCommand {
    Order order; OrderItem item;
    RemoveItemCommand(Order o, OrderItem i) { order = o; item = i; }
    public void execute() { order.items.remove(item); }
    public void undo() { order.items.add(item); }
}

// ===== STATE PATTERN =====
// Real state classes now, replacing the old ordinal-increment hack.
// Each class owns its own legal transition and throws on illegal ones.
interface OrderState { void advance(Order o); }
class PlacedState implements OrderState {
    public void advance(Order o) { o.status = OrderStatus.IN_PREPARATION; o.setState(new InPreparationState()); }
}
class InPreparationState implements OrderState {
    public void advance(Order o) { o.status = OrderStatus.READY; o.setState(new ReadyState()); }
}
class ReadyState implements OrderState {
    public void advance(Order o) { o.status = OrderStatus.SERVED; o.setState(new ServedState()); }
}
class ServedState implements OrderState {
    public void advance(Order o) { o.status = OrderStatus.COMPLETED; o.setState(new CompletedState()); }
}
class CompletedState implements OrderState {
    public void advance(Order o) { throw new RuntimeException("Order already completed"); }
}

class Order {
    static int seqCounter = 0;
    String id; Table table; List<OrderItem> items = new ArrayList<>();
    OrderStatus status = OrderStatus.PLACED;
    OrderState state = new PlacedState(); // STATE PATTERN: drives transitions
    List<OrderCommand> history = new ArrayList<>();
    int priority; // used by Strategy below (e.g. VIP table = higher priority)
    int sequence; // FIFO tiebreaker
    Order(String id, Table table) {
        this.id = id; this.table = table; this.sequence = seqCounter++;
    }
    void setState(OrderState s) { this.state = s; }
    void executeCommand(OrderCommand cmd) { cmd.execute(); history.add(cmd); } // COMMAND PATTERN: call site
    void undoLast() { if (!history.isEmpty()) history.remove(history.size() - 1).undo(); } // COMMAND PATTERN: undo call site
    double total() { return items.stream().mapToDouble(OrderItem::subtotal).sum(); }
    void advance() { state.advance(this); } // STATE PATTERN: delegates instead of touching enum directly
}

// ===== STRATEGY PATTERN =====
// Interchangeable ordering algorithm for the kitchen queue. Step 6 promised
// "priority" — this is what actually implements it instead of a plain FIFO list.
interface PriorityStrategy { Comparator<Order> comparator(); }
class FifoPriority implements PriorityStrategy {
    public Comparator<Order> comparator() { return Comparator.comparingInt(o -> o.sequence); }
}
class VipPriority implements PriorityStrategy {
    public Comparator<Order> comparator() {
        return Comparator.comparingInt((Order o) -> -o.priority).thenComparingInt(o -> o.sequence);
    }
}

class Kitchen {
    Queue<Order> queue; // now a real priority queue, not a LinkedList
    List<Chef> chefs = new ArrayList<>();
    Kitchen(PriorityStrategy strategy) { this.queue = new PriorityQueue<>(strategy.comparator()); } // STRATEGY PATTERN: injected here
    void addChef(Chef c) { chefs.add(c); }
    void receiveOrder(Order o) { queue.add(o); o.advance(); } // now goes through STATE PATTERN, not raw enum set
    Order prepareNext() {
        Order o = queue.poll();
        if (o != null) o.advance(); // Placed/InPreparation -> Ready via state transition
        return o;
    }
}

// ===== OBSERVER PATTERN =====
interface Observer { void notify(String message); }
class EmailObserver implements Observer {
    String email;
    EmailObserver(String email) { this.email = email; }
    public void notify(String message) { System.out.println("Email to " + email + ": " + message); }
}

// Bill was in "Key Classes" but never defined — checkout used to return a raw double.
class Bill {
    Order order; double subtotal, tax, total;
    static final double TAX_RATE = 0.08;
    Bill(Order order) {
        this.order = order;
        this.subtotal = order.total();
        this.tax = subtotal * TAX_RATE;
        this.total = subtotal + tax;
    }
}

class Restaurant {
    String name; List<Table> tables = new ArrayList<>(); List<MenuItem> menu = new ArrayList<>();
    List<Waiter> waiters = new ArrayList<>();
    List<Reservation> reservations = new ArrayList<>();
    List<Observer> observers = new ArrayList<>(); // OBSERVER PATTERN: subject holds subscribers
    Kitchen kitchen; Map<String, Order> orders = new HashMap<>();

    Restaurant(String name, PriorityStrategy strategy) { this.name = name; this.kitchen = new Kitchen(strategy); }
    void addTable(Table t) { tables.add(t); }
    void addMenuItem(MenuItem m) { menu.add(m); }
    void addWaiter(Waiter w) { waiters.add(w); }
    void addObserver(Observer o) { observers.add(o); } // OBSERVER PATTERN: registration
    void notifyAll(String msg) { observers.forEach(o -> o.notify(msg)); } // OBSERVER PATTERN: broadcast

    Reservation reserveTable(String guestName, Table t, LocalDateTime dateTime) {
        t.status = TableStatus.RESERVED;
        Reservation r = new Reservation(guestName, t, dateTime);
        reservations.add(r);
        return r;
    }

    Table seatGuests(int partySize) {
        Table t = tables.stream().filter(tb -> tb.status == TableStatus.AVAILABLE && tb.capacity >= partySize)
            .min(Comparator.comparingInt(tb -> tb.capacity)).orElse(null);
        if (t != null) {
            t.status = TableStatus.OCCUPIED;
            if (!waiters.isEmpty()) t.assignedWaiter = waiters.get(0); // assigns a waiter, was missing before
        }
        return t;
    }

    Order createOrder(Table t) {
        Order o = new Order(UUID.randomUUID().toString().substring(0, 8), t);
        orders.put(o.id, o);
        notifyAll("Order placed for table " + t.number); // OBSERVER PATTERN: call site
        return o;
    }

    void submitToKitchen(Order o) { kitchen.receiveOrder(o); }

    Bill checkout(String orderId) {
        Order o = orders.remove(orderId);
        o.advance(); // Served -> Completed via STATE PATTERN
        o.table.status = TableStatus.AVAILABLE;
        Bill bill = new Bill(o);
        notifyAll("Bill ready for table " + o.table.number); // OBSERVER PATTERN: call site
        return bill;
    }
}

// Usage:
// Restaurant r = new Restaurant("Bistro", new FifoPriority()); // STRATEGY PATTERN: chosen here
// r.addTable(new Table(1, 4));
// r.addMenuItem(new MenuItem("Pasta", 12.99, "Main"));
// r.addWaiter(new Waiter("W1", "Sam"));
// r.kitchen.addChef(new Chef("CH1", "Marco"));
// r.addObserver(new EmailObserver("guest@mail.com")); // OBSERVER PATTERN: registered here
//
// r.reserveTable("Alice", r.tables.get(0), LocalDateTime.now().plusHours(2)); // Reservation actually used
//
// Table t = r.seatGuests(2);
// Order o = r.createOrder(t);
// // -> notifyAll fires -> prints "Email to guest@mail.com: Order placed for table 1"
//
// o.executeCommand(new AddItemCommand(o, new OrderItem(r.menu.get(0), 2, "no garlic"))); // COMMAND PATTERN: call site
// // o.undoLast(); // would remove the item just added, via undo()
//
// r.submitToKitchen(o); // -> o.advance() -> STATE PATTERN: Placed -> InPreparation
// r.kitchen.prepareNext(); // -> o.advance() -> InPreparation -> Ready
// o.advance(); // Ready -> Served (would normally be called by Waiter delivering food)
//
// Bill bill = r.checkout(o.id); // -> o.advance() -> Served -> Completed; Bill computed with tax
// // -> notifyAll fires -> prints "Email to guest@mail.com: Bill ready for table 1"
