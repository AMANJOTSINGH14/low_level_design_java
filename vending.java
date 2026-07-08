import java.util.*;

// ============================================================================
// VENDING MACHINE — Low-Level Design using the STATE pattern
// Core idea: the machine's behavior on each action (select / insert / dispense
// / cancel) depends on WHICH state it's in. Instead of a giant if/else on a
// "status" field, each state is its own class that knows how to handle every
// action. Transitions = swapping the current state object.
// ============================================================================


// Coin denominations. Storing value in cents avoids floating-point money bugs.
// Using an enum guarantees only valid coins can ever be inserted.
enum Coin { PENNY(1), NICKEL(5), DIME(10), QUARTER(25), DOLLAR(100);
    int value; Coin(int v) { this.value = v; }
}

// A product slot: identified by a keypad code (e.g. "A1"), with a display name
// and a price. Price is in cents for the same money-safety reason as Coin.
class Product {
    String code, name; int priceInCents;
    Product(String code, String name, int price) { this.code = code; this.name = name; this.priceInCents = price; }
}

// Inventory is deliberately a SEPARATE class from VendingMachine (Single
// Responsibility). It only tracks "how many of each code are left" — it doesn't
// know about coins, states, or prices. This makes it trivially unit-testable.
class Inventory {
    Map<String, Integer> stock = new HashMap<>();                        // code -> remaining quantity
    void restock(String code, int qty) { stock.merge(code, qty, Integer::sum); }   // merge = add to existing count (or start at qty)
    boolean isAvailable(String code) { return stock.getOrDefault(code, 0) > 0; }    // getOrDefault avoids NPE on unknown codes
    void dispense(String code) { stock.merge(code, -1, Integer::sum); }             // decrement on a successful sale
}

// ---------------------------------------------------------------------------
// STATE pattern — the contract every concrete state must fulfill.
// Every state must answer ALL four actions, even if the answer is "you can't do
// that right now." That completeness is the whole point: no action is ever
// undefined, so we never fall through to buggy default behavior.
// ---------------------------------------------------------------------------
interface VendingState {
    void selectProduct(VendingMachine vm, String code);
    void insertCoin(VendingMachine vm, Coin coin);
    void dispense(VendingMachine vm);
    void cancel(VendingMachine vm);
}

// STATE 1 — IDLE: nothing selected yet, waiting for the user to pick a product.
// Only selectProduct does real work here; every other action is rejected.
class IdleState implements VendingState {
    public void selectProduct(VendingMachine vm, String code) {
        Product p = vm.products.get(code);
        if (p == null) { System.out.println("Invalid code"); return; }              // guard: unknown keypad code
        if (!vm.inventory.isAvailable(code)) { System.out.println("Out of stock"); return; } // guard: sold out
        vm.selectedProduct = p;                                                     // remember what was chosen
        vm.setState(new ProductSelectedState());                                    // TRANSITION: Idle -> ProductSelected
        System.out.println("Selected: " + p.name + " ($" + p.priceInCents / 100.0 + ")");
    }
    // In Idle you haven't chosen anything, so coins/dispense/cancel make no sense.
    public void insertCoin(VendingMachine vm, Coin c) { System.out.println("Select product first"); }
    public void dispense(VendingMachine vm) { System.out.println("Select product first"); }
    public void cancel(VendingMachine vm) { System.out.println("Nothing to cancel"); }
}

// STATE 2 — PRODUCT SELECTED: a product is chosen, now we collect money.
// This is where the paying loop lives.
class ProductSelectedState implements VendingState {
    public void selectProduct(VendingMachine vm, String code) { System.out.println("Already selected"); } // lock the choice once paying starts
    public void insertCoin(VendingMachine vm, Coin coin) {
        vm.insertedAmount += coin.value;                                            // accumulate what the user has paid
        vm.coinBox.merge(coin, 1, Integer::sum);                                    // physically hold the coin (for refund/change accounting)
        int remaining = vm.selectedProduct.priceInCents - vm.insertedAmount;
        if (remaining <= 0) vm.setState(new DispensingState());                     // TRANSITION: enough money -> Dispensing
        else System.out.println("Insert " + remaining + " more cents");             // still short: prompt for the difference
    }
    public void dispense(VendingMachine vm) { System.out.println("Insert more coins"); } // can't dispense until fully paid
    public void cancel(VendingMachine vm) { vm.refund(); vm.reset(); }              // user backs out: give the money back, return to Idle
}

// STATE 3 — DISPENSING: fully paid, just waiting for the physical dispense call.
// Selecting/inserting here is ignored because the transaction is committed.
class DispensingState implements VendingState {
    public void selectProduct(VendingMachine vm, String c) { System.out.println("Dispensing..."); }
    public void insertCoin(VendingMachine vm, Coin c) { System.out.println("Dispensing..."); }
    public void dispense(VendingMachine vm) {
        vm.inventory.dispense(vm.selectedProduct.code);                             // decrement stock (the actual sale)
        int change = vm.insertedAmount - vm.selectedProduct.priceInCents;           // overpayment, if any
        System.out.println("Dispensed: " + vm.selectedProduct.name);
        if (change > 0) System.out.println("Change: " + change + " cents");
        vm.reset();                                                                 // TRANSITION: back to a clean Idle for the next customer
    }
    public void cancel(VendingMachine vm) { vm.refund(); vm.reset(); }              // still allow cancel right up until dispense fires
}

// ---------------------------------------------------------------------------
// CONTEXT class. Holds all shared data (products, inventory, coins, running
// total) and the CURRENT state. Its public methods just DELEGATE to the state
// object — the machine itself contains no branching logic. That delegation is
// the signature of the State pattern.
// ---------------------------------------------------------------------------
class VendingMachine {
    Map<String, Product> products = new HashMap<>();          // code -> product catalog
    Inventory inventory = new Inventory();                    // stock counts, kept separate (SRP)
    Map<Coin, Integer> coinBox = new EnumMap<>(Coin.class);   // coins currently held this transaction; EnumMap = fast + ordered for enum keys
    VendingState state;                                       // the current state object — this is what changes
    Product selectedProduct;                                  // scratch data for the in-progress transaction
    int insertedAmount = 0;                                   // running total paid so far, in cents

    VendingMachine() { state = new IdleState(); }             // machines always boot into Idle

    void setState(VendingState s) { this.state = s; }         // states call this on themselves to trigger a transition

    void addProduct(Product p, int qty) { products.put(p.code, p); inventory.restock(p.code, qty); } // register catalog + stock together

    // --- Public API: every call simply forwards to the current state ---------
    void selectProduct(String code) { state.selectProduct(this, code); }
    void insertCoin(Coin coin) { state.insertCoin(this, coin); }
    void dispense() { state.dispense(this); }
    void cancel() { state.cancel(this); }

    // Return coins for a cancelled/aborted transaction.
    void refund() {
        if (insertedAmount > 0) System.out.println("Refunding: " + insertedAmount + " cents");
    }

    // Wipe per-transaction scratch state and return to Idle for the next user.
    // Called after both a successful dispense AND a cancel.
    void reset() {
        selectedProduct = null; insertedAmount = 0;
        coinBox.clear(); state = new IdleState();
    }

    // Convenience read: which products are actually buyable right now.
    List<Product> getAvailableProducts() {
        List<Product> avail = new ArrayList<>();
        for (Product p : products.values())
            if (inventory.isAvailable(p.code)) avail.add(p);
        return avail;
    }
}

// Usage:
// VendingMachine vm = new VendingMachine();
// vm.addProduct(new Product("A1", "Cola", 150), 10);
// vm.addProduct(new Product("A2", "Chips", 100), 5);
// vm.selectProduct("A1");           // Idle -> ProductSelected
// vm.insertCoin(Coin.DOLLAR);       // paid 100, need 50 more
// vm.insertCoin(Coin.QUARTER);      // paid 125, need 25 more
// vm.insertCoin(Coin.QUARTER);      // paid 150 -> ProductSelected -> Dispensing
// vm.dispense();                    // Dispensed: Cola, Change: 0 -> reset -> Idle