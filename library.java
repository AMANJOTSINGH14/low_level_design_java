import java.util.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/*
 * ============================================================================
 *  LIBRARY MANAGEMENT SYSTEM  —  Low Level Design (FINAL)
 * ============================================================================
 *  All FOUR patterns are now genuinely implemented (not just claimed):
 *
 *    [X] OBSERVER   -> BookItem is the Subject; waiting Members are observers.
 *                      Notifications are PER-COPY, so only members who reserved
 *                      THIS book get alerted (fixes the old broadcast-to-all bug).
 *    [X] FACTORY    -> AccountFactory & BookItemFactory centralize object creation.
 *    [X] STRATEGY   -> FineStrategy is swappable at runtime (daily / flat / tiered).
 *    [X] DECORATOR  -> Notifier channels are stackable (console + email + SMS).
 *
 *  Every pattern site is tagged inline with:  <<< PATTERN: ... >>>
 * ============================================================================
 */

enum BookStatus    { AVAILABLE, RESERVED, LOANED, LOST }
enum AccountStatus { ACTIVE, CLOSED, BLACKLISTED }
enum AccountType   { MEMBER, LIBRARIAN }

/* ==========================================================================
 *  OBSERVER PATTERN
 * ========================================================================== */

// The Observer (subscriber) contract — anyone who wants event notifications.
interface Observer { void update(String msg); }

/*
 * <<< PATTERN: OBSERVER — Subject contract >>>
 * A Subject keeps its own list of observers and broadcasts to just them.
 * We make BookItem the Subject so notifications are scoped to a single copy.
 */
interface Subject {
    void addObserver(Observer o);
    void removeObserver(Observer o);
    void notifyObservers(String msg);
}

/* ==========================================================================
 *  DECORATOR PATTERN  (notification channels)
 * ========================================================================== */

/*
 * <<< PATTERN: DECORATOR — Component interface >>>
 * The base "thing that can send a message". Decorators wrap this to add extra
 * delivery channels without modifying the underlying object.
 */
interface Notifier { void send(String msg); }

// Concrete component: the plain, undecorated channel.
class ConsoleNotifier implements Notifier {
    private final String name;
    ConsoleNotifier(String name) { this.name = name; }
    public void send(String msg) { System.out.println("[console] " + name + ": " + msg); }
}

/*
 * <<< PATTERN: DECORATOR — abstract decorator >>>
 * Holds a reference to the wrapped Notifier and delegates to it, so each
 * subclass can add its own channel on top and still call through.
 */
abstract class NotifierDecorator implements Notifier {
    protected final Notifier wrapped;
    NotifierDecorator(Notifier wrapped) { this.wrapped = wrapped; }
    public void send(String msg) { wrapped.send(msg); }  // pass down the chain
}

// Concrete decorator: adds an email channel on top of whatever it wraps.
class EmailNotifier extends NotifierDecorator {
    private final String email;
    EmailNotifier(Notifier wrapped, String email) { super(wrapped); this.email = email; }
    public void send(String msg) {
        super.send(msg);                                   // keep prior channels
        System.out.println("[email -> " + email + "] " + msg);
    }
}

// Concrete decorator: adds an SMS channel on top of whatever it wraps.
class SMSNotifier extends NotifierDecorator {
    private final String phone;
    SMSNotifier(Notifier wrapped, String phone) { super(wrapped); this.phone = phone; }
    public void send(String msg) {
        super.send(msg);
        System.out.println("[sms -> " + phone + "] " + msg);
    }
}

/* ==========================================================================
 *  CORE DOMAIN
 * ========================================================================== */

// Book = METADATA of a work (title/author/ISBN). One Book -> many copies.
class Book {
    String isbn, title, author;
    Book(String isbn, String title, String author) {
        this.isbn = isbn; this.title = title; this.author = author;
    }
}

/*
 * BookItem = a PHYSICAL copy identified by barcode.
 * <<< PATTERN: OBSERVER — concrete Subject >>>
 * Each copy owns its OWN waitlist. Only members who reserved this copy are
 * observers of it, so notifications are precise instead of global.
 */
class BookItem implements Subject {
    String barcode;
    Book book;
    BookStatus status;
    LocalDate dueDate;
    private final List<Observer> waitlist = new ArrayList<>();  // reservation queue

    BookItem(String barcode, Book book) {
        this.barcode = barcode; this.book = book;
        this.status = BookStatus.AVAILABLE;
    }
    boolean isAvailable() { return status == BookStatus.AVAILABLE; }

    public void addObserver(Observer o)    { waitlist.add(o); }
    public void removeObserver(Observer o) { waitlist.remove(o); }
    public void notifyObservers(String msg) {
        // snapshot + clear: once notified, the hold is consumed
        List<Observer> toNotify = new ArrayList<>(waitlist);
        waitlist.clear();
        toNotify.forEach(o -> o.update(msg));
    }
}

// Abstract account: shared identity, differing permissions via subclassing.
abstract class Account {
    String id, name, email;
    AccountStatus status = AccountStatus.ACTIVE;
    Account(String id, String name, String email) {
        this.id = id; this.name = name; this.email = email;
    }
}

/*
 * Member = a borrower.
 * <<< PATTERN: OBSERVER — concrete Observer >>>
 * <<< PATTERN: DECORATOR — client of the notifier chain >>>
 * update() routes the message through this member's decorated Notifier,
 * so how a member is reached (console/email/SMS) is composable per member.
 */
class Member extends Account implements Observer {
    List<BookItem> borrowed = new ArrayList<>();
    static final int MAX_BOOKS = 5;
    private final Notifier notifier;   // built by the factory (decorated chain)

    Member(String id, String name, String email, Notifier notifier) {
        super(id, name, email);
        this.notifier = notifier;
    }
    boolean canBorrow() {
        return borrowed.size() < MAX_BOOKS && status == AccountStatus.ACTIVE;
    }
    public void update(String msg) {
        notifier.send("Hi " + name + " — " + msg);   // delegate to decorator chain
    }
}

// Librarian = staff with catalog-mutation permission (not a borrower/observer).
class Librarian extends Account {
    Librarian(String id, String name, String email) { super(id, name, email); }
    void addBookItem(Library lib, BookItem item) { lib.catalog.put(item.barcode, item); }
}

/* ==========================================================================
 *  FACTORY PATTERN
 * ========================================================================== */

/*
 * <<< PATTERN: FACTORY — Account creation >>>
 * Callers ask for an account by TYPE and never touch `new Member/Librarian`.
 * It also builds each member's decorated Notifier chain in one place, so the
 * decoration policy lives here instead of being scattered around the code.
 */
class AccountFactory {
    static Account createAccount(AccountType type, String id, String name, String email) {
        switch (type) {
            case MEMBER:
                // Console channel, decorated with Email then SMS = all three fire.
                Notifier chain = new SMSNotifier(
                                    new EmailNotifier(
                                        new ConsoleNotifier(name), email),
                                    "+1-555-0100");
                return new Member(id, name, email, chain);
            case LIBRARIAN:
                return new Librarian(id, name, email);
            default:
                throw new IllegalArgumentException("Unknown account type: " + type);
        }
    }
}

/*
 * <<< PATTERN: FACTORY — BookItem creation >>>
 * Auto-generates a unique barcode so callers don't invent their own scheme.
 */
class BookItemFactory {
    static BookItem createBookItem(Book book) {
        String barcode = "BC-" + UUID.randomUUID().toString().substring(0, 8);
        return new BookItem(barcode, book);
    }
}

/* ==========================================================================
 *  RESERVATION & LENDING RECORDS
 * ========================================================================== */

class BookReservation {
    String reservationId; BookItem item; Member member; LocalDate date;
    BookReservation(BookItem item, Member member) {
        this.reservationId = UUID.randomUUID().toString().substring(0, 8);
        this.item = item; this.member = member; this.date = LocalDate.now();
    }
}

class BookLending {
    BookItem item; Member member; LocalDate issueDate, dueDate;
    BookLending(BookItem item, Member member, int days) {
        this.item = item; this.member = member;
        this.issueDate = LocalDate.now();
        this.dueDate   = issueDate.plusDays(days);
        item.dueDate   = this.dueDate;   // keep copy + loan in sync
    }
}

/* ==========================================================================
 *  STRATEGY PATTERN  (fine calculation)
 * ========================================================================== */

/*
 * <<< PATTERN: STRATEGY — algorithm interface >>>
 * The fine rule is now an injectable object, so the Library can swap
 * daily/flat/tiered pricing at runtime without changing return logic.
 */
interface FineStrategy { double calculate(BookLending lending); }

// Concrete strategy: charge per overdue day at a configurable rate.
class DailyRateFine implements FineStrategy {
    private final double rate;
    DailyRateFine(double rate) { this.rate = rate; }
    public double calculate(BookLending l) {
        long overdue = ChronoUnit.DAYS.between(l.dueDate, LocalDate.now());
        return overdue > 0 ? overdue * rate : 0;
    }
}

// Concrete strategy: single flat fee the moment it's overdue.
class FlatFeeFine implements FineStrategy {
    private final double fee;
    FlatFeeFine(double fee) { this.fee = fee; }
    public double calculate(BookLending l) {
        long overdue = ChronoUnit.DAYS.between(l.dueDate, LocalDate.now());
        return overdue > 0 ? fee : 0;
    }
}

// Concrete strategy: escalating rate — cheap early, pricier the longer it's late.
class TieredFine implements FineStrategy {
    public double calculate(BookLending l) {
        long d = ChronoUnit.DAYS.between(l.dueDate, LocalDate.now());
        if (d <= 0)  return 0;
        if (d <= 7)  return d * 0.50;                          // first week: 50c/day
        if (d <= 30) return 7 * 0.50 + (d - 7) * 1.00;         // then $1/day
        return 7 * 0.50 + 23 * 1.00 + (d - 30) * 2.00;         // then $2/day
    }
}

/* ==========================================================================
 *  LIBRARY  (facade / orchestrator)
 * ========================================================================== */

class Library {
    Map<String, BookItem>    catalog        = new HashMap<>();
    Map<String, BookLending> activeLendings = new HashMap<>();

    // <<< PATTERN: STRATEGY — the strategy is held as state and swappable >>>
    private FineStrategy fineStrategy;
    Library(FineStrategy fineStrategy) { this.fineStrategy = fineStrategy; }
    void setFineStrategy(FineStrategy s) { this.fineStrategy = s; }  // swap at runtime

    List<BookItem> searchByTitle(String title) {
        List<BookItem> results = new ArrayList<>();
        for (BookItem bi : catalog.values())
            if (bi.book.title.toLowerCase().contains(title.toLowerCase()))
                results.add(bi);
        return results;
    }

    BookLending lendBook(String barcode, Member member) {
        BookItem item = catalog.get(barcode);
        if (item == null || !item.isAvailable() || !member.canBorrow()) return null;
        item.status = BookStatus.LOANED;
        BookLending lending = new BookLending(item, member, 14);
        member.borrowed.add(item);
        activeLendings.put(barcode, lending);
        return lending;
    }

    double returnBook(String barcode) {
        BookLending lending = activeLendings.remove(barcode);
        if (lending == null) throw new RuntimeException("No active lending");
        BookItem item = lending.item;
        item.status = BookStatus.AVAILABLE;
        lending.member.borrowed.remove(item);

        // <<< PATTERN: STRATEGY — delegate fine math to the injected rule >>>
        double fine = fineStrategy.calculate(lending);

        // <<< PATTERN: OBSERVER — notify ONLY members waiting on THIS copy >>>
        item.notifyObservers("'" + item.book.title + "' is now available to borrow");
        return fine;
    }

    /*
     * Reserve: only if the copy is currently out. Registers the member on the
     * copy's waitlist so they'll be the ones alerted when it returns.
     */
    BookReservation reserveBook(String barcode, Member member) {
        BookItem item = catalog.get(barcode);
        if (item == null || item.isAvailable()) return null;
        item.status = BookStatus.RESERVED;
        item.addObserver(member);   // <<< PATTERN: OBSERVER — subscribe to this copy
        return new BookReservation(item, member);
    }
}

/* ==========================================================================
 *  DEMO
 * ========================================================================== */
class Demo {
    public static void main(String[] args) {
        // STRATEGY: start with tiered pricing (swap any time with setFineStrategy).
        Library lib = new Library(new TieredFine());

        // FACTORY: create copies and accounts without any direct `new`.
        Book book   = new Book("978-0", "Design Patterns", "GoF");
        BookItem bi = BookItemFactory.createBookItem(book);
        lib.catalog.put(bi.barcode, bi);

        Member alice = (Member) AccountFactory.createAccount(
                            AccountType.MEMBER, "M1", "Alice", "alice@mail.com");
        Member bob   = (Member) AccountFactory.createAccount(
                            AccountType.MEMBER, "M2", "Bob", "bob@mail.com");

        // Alice borrows the only copy.
        lib.lendBook(bi.barcode, alice);

        // OBSERVER: Bob reserves it -> he subscribes to THIS copy's waitlist.
        lib.reserveBook(bi.barcode, bob);

        // Alice returns it -> only Bob is notified (via his DECORATOR chain).
        double fine = lib.returnBook(bi.barcode);
        System.out.println("Fine charged to Alice: $" + fine);
    }
}




// little disscussion on decorators
//   Full trace for:
// javaCoffee big = new Cream(new Sugar(new Milk(new SimpleCoffee())));
// System.out.println(big.description() + " = $" + big.cost());
// Two phases: building first, then calling cost().

// PHASE 1 — Building the object (uses super)
// Java builds arguments innermost-first. So the order is: SimpleCoffee → Milk → Sugar → Cream.
// Step 1 — new SimpleCoffee()
// SimpleCoffee constructor runs → creates object S (holds nothing)
// Step 2 — new Milk(S)
// javaMilk(Coffee c) { super(c); }        // c = S
//    → super(c) jumps to parent:
//      CoffeeDecorator(Coffee coffee) { this.coffee = coffee; }   // coffee = S
//    → M.coffee = S
// Object M built. M.coffee → S.
// Step 3 — new Sugar(M)
// javaSugar(Coffee c) { super(c); }       // c = M
//    → super(c) → CoffeeDecorator stores this.coffee = M
//    → G.coffee = M
// Object G (Sugar) built. G.coffee → M.
// Step 4 — new Cream(G)
// javaCream(Coffee c) { super(c); }       // c = G
//    → super(c) → CoffeeDecorator stores this.coffee = G
//    → C.coffee = G
// Object C (Cream) built. C.coffee → G.
// After Phase 1 — four objects, each holding ONE inner one:
// C (Cream)  .coffee → G
// G (Sugar)  .coffee → M
// M (Milk)   .coffee → S
// S (SimpleCoffee)   → holds nothing (end)
// big points to C.

// PHASE 2 — big.cost() (the calculation)
// big is C (Cream), so Cream.cost() runs first. Each layer must fetch the inner cost before adding its own.
// Step 1 — Cream.cost()
// javareturn coffee.cost() + 0.75;   // coffee = G (Sugar)
// Can't add 0.75 yet → must call G.cost() first. PAUSE, go inward.
// Step 2 — Sugar.cost()
// javareturn coffee.cost() + 0.25;   // coffee = M (Milk)
// Must call M.cost() first. PAUSE, go inward.
// Step 3 — Milk.cost()
// javareturn coffee.cost() + 0.50;   // coffee = S (SimpleCoffee)
// Must call S.cost() first. PAUSE, go inward.
// Step 4 — SimpleCoffee.cost()
// javareturn 2.00;   // no coffee field, asks nobody → BOTTOM
// Returns 2.00. Now everything unwinds back up.
// Step 5 — back in Milk.cost() (was paused at Step 3)
// coffee.cost() gave 2.00
// return 2.00 + 0.50 = 2.50
// Step 6 — back in Sugar.cost() (paused at Step 2)
// coffee.cost() gave 2.50
// return 2.50 + 0.25 = 2.75
// Step 7 — back in Cream.cost() (paused at Step 1)
// coffee.cost() gave 2.75
// return 2.75 + 0.75 = 3.50
// Result
// big.cost() = 3.50

// The exact order, both directions
// Dive in (each pauses, asks the inner one):
// Cream.cost → Sugar.cost → Milk.cost → SimpleCoffee.cost
// Bubble back (values return, each adds its part):
// 2.00 (SimpleCoffee)
//   → +0.50 = 2.50 (Milk)
//     → +0.25 = 2.75 (Sugar)
//       → +0.75 = 3.50 (Cream)
// The one idea: each cost() goes all the way down to get 2.00 from SimpleCoffee, then on the way back up each wrapper adds its own price — 0.50, 0.25, 0.75 — landing on 3.50.in phase 1 it measn therer are 4 decorator object in heap holding inner coffeeAlmost — but one important correction: there are 4 objects in the heap, but only 3 of them are decorators. SimpleCoffee is not a decorator.
// C (Cream)         ← decorator, holds coffee → G
// G (Sugar)         ← decorator, holds coffee → M
// M (Milk)          ← decorator, holds coffee → S
// S (SimpleCoffee)  ← NOT a decorator, holds nothing
// Why SimpleCoffee isn't a decorator:
// javaclass SimpleCoffee implements Coffee { ... }   // implements the interface, has NO coffee field
// class Milk  extends CoffeeDecorator { ... }    // extends decorator, HAS a coffee field
// A decorator = it wraps something, so it has a coffee field pointing inward. SimpleCoffee wraps nothing — it's the real base object at the center. It only returns 2.00. It's the thing being decorated, not a decorator itself.
// So the accurate statement:
// 4 objects in the heap. 3 are decorators (Cream, Sugar, Milk), each holding a reference to the next inner object. 1 is the base component (SimpleCoffee), holding nothing — it's the end of the chain.
// Picture it as a chain of references in the heap:
// big ──► [Cream]──coffee──► [Sugar]──coffee──► [Milk]──coffee──► [SimpleCoffee]
//          decorator          decorator          decorator          base (no coffee)
// Each decorator holds exactly one inner reference (not all of them) — that's the chain. And the innermost thing they eventually point to is the non-decorator base, SimpleCoffee, which stops the chain.
