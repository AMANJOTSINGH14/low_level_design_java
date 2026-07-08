import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/* ============================ Domain ============================ */

enum TransactionType { WITHDRAW, DEPOSIT, BALANCE_INQUIRY }

/** Immutable card: carries only what is needed to reach an account. */
final class Card {
    private final String cardNumber;
    private final String accountId;
    Card(String cardNumber, String accountId) {
        this.cardNumber = cardNumber;
        this.accountId = accountId;
    }
    String getCardNumber() { return cardNumber; }
    String getAccountId()  { return accountId; }
}

/** Bank-side account. The BANK — not the ATM — owns balance and auth. */
final class Account {
    private final String accountId;
    private final String pin;        // demo only; production stores a salted hash
    private long balance;            // whole currency units; for cents use BigDecimal
    final ReentrantLock lock = new ReentrantLock();

    Account(String accountId, String pin, long balance) {
        this.accountId = accountId;
        this.pin = pin;
        this.balance = balance;
    }
    String getAccountId() { return accountId; }
    boolean matchesPin(String candidate) { return pin.equals(candidate); }
    long getBalance() { return balance; }
    void credit(long amount) { balance += amount; }
    void debit(long amount)  { balance -= amount; }
}

/* ========================= Bank backend ========================= */

interface BankService {
    boolean authenticate(String accountId, String pin);
    long getBalance(String accountId);
    boolean withdraw(String accountId, long amount); // false => insufficient funds
    void deposit(String accountId, long amount);
}

/** Thread-safe: many ATMs may hit the same account concurrently. */
final class InMemoryBankService implements BankService {
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    void addAccount(Account a) { accounts.put(a.getAccountId(), a); }

    private Account require(String id) {
        Account a = accounts.get(id);
        if (a == null) throw new NoSuchElementException("Unknown account: " + id);
        return a;
    }

    @Override public boolean authenticate(String accountId, String pin) {
        return require(accountId).matchesPin(pin);
    }

    @Override public long getBalance(String accountId) {
        Account a = require(accountId);
        a.lock.lock();
        try { return a.getBalance(); }
        finally { a.lock.unlock(); }
    }

    /** Atomic check-then-debit: two ATMs cannot overdraw the same account. */
    @Override public boolean withdraw(String accountId, long amount) {
        Account a = require(accountId);
        a.lock.lock();
        try {
            if (a.getBalance() < amount) return false;
            a.debit(amount);
            return true;
        } finally { a.lock.unlock(); }
    }

    @Override public void deposit(String accountId, long amount) {
        Account a = require(accountId);
        a.lock.lock();
        try { a.credit(amount); }
        finally { a.lock.unlock(); }
    }
}

/* ===================== Cash dispenser (CoR) ===================== */

/**
 * One link of the dispensing chain; owns ONE denomination + its note count.
 * A single parameterized class (vs. one subclass per note) keeps the chain
 * trivial to reconfigure — still the Chain-of-Responsibility pattern.
 */
final class DenominationNode {
    private final int denomination;
    private int noteCount;
    private DenominationNode next;

    DenominationNode(int denomination, int noteCount) {
        this.denomination = denomination;
        this.noteCount = noteCount;
    }
    void setNext(DenominationNode next) { this.next = next; }

    /** Pass 1: compute a breakdown WITHOUT mutating state. */
    boolean plan(int amount, Map<Integer, Integer> plan) {
        int use = Math.min(amount / denomination, noteCount);
        if (use > 0) plan.put(denomination, use);
        int remaining = amount - use * denomination;
        if (remaining == 0) return true;
        return next != null && next.plan(remaining, plan);
    }

    /** Pass 2: apply a previously validated breakdown. */
    void apply(Map<Integer, Integer> plan) {
        Integer use = plan.get(denomination);
        if (use != null) noteCount -= use;
        if (next != null) next.apply(plan);
    }

    void refill(int denom, int count) {
        if (denom == denomination) noteCount += count;
        else if (next != null) next.refill(denom, count);
    }
}

final class CashDispenser {
    private final DenominationNode head; // highest denomination first

    /** stock MUST be inserted in DESCENDING denomination order (greedy needs it). */
    CashDispenser(LinkedHashMap<Integer, Integer> stock) {
        DenominationNode prev = null, first = null;
        for (Map.Entry<Integer, Integer> e : stock.entrySet()) {
            DenominationNode node = new DenominationNode(e.getKey(), e.getValue());
            if (first == null) first = node;
            if (prev != null) prev.setNext(node);
            prev = node;
        }
        if (first == null) throw new IllegalArgumentException("No denominations configured");
        this.head = first;
    }

    /** Greedy plan; correct for canonical, divisibility-chained denominations. */
    Optional<Map<Integer, Integer>> tryDispense(int amount) {
        Map<Integer, Integer> plan = new LinkedHashMap<>();
        return head.plan(amount, plan) ? Optional.of(plan) : Optional.empty();
    }

    void commit(Map<Integer, Integer> plan) { head.apply(plan); }
    void refill(int denomination, int count) { head.refill(denomination, count); }
}

/* ====================== State pattern ====================== */

interface ATMState {
    void insertCard(ATM atm, Card card);
    void enterPin(ATM atm, String pin);
    void selectOperation(ATM atm, TransactionType type, long amount);
    void ejectCard(ATM atm);
}

/** Default: every operation is invalid. States override only what they allow. */
abstract class AbstractATMState implements ATMState {
    public void insertCard(ATM atm, Card card) { reject("insert card"); }
    public void enterPin(ATM atm, String pin)  { reject("enter PIN"); }
    public void selectOperation(ATM atm, TransactionType t, long amt) { reject("select operation"); }
    public void ejectCard(ATM atm)             { reject("eject card"); }
    private void reject(String action) {
        throw new IllegalStateException(
            "Cannot " + action + " in state " + getClass().getSimpleName());
    }
}

final class IdleState extends AbstractATMState {
    @Override public void insertCard(ATM atm, Card card) {
        atm.setCurrentCard(card);
        atm.resetPinAttempts();
        System.out.println("Card accepted. Please enter your PIN.");
        atm.setState(atm.getHasCardState());
    }
}

final class HasCardState extends AbstractATMState {
    private static final int MAX_ATTEMPTS = 3;

    @Override public void enterPin(ATM atm, String pin) {
        Card card = atm.getCurrentCard();
        if (atm.getBankService().authenticate(card.getAccountId(), pin)) {
            System.out.println("PIN verified.");
            atm.setState(atm.getAuthenticatedState());
            return;
        }
        atm.incrementPinAttempts();
        if (atm.getPinAttempts() >= MAX_ATTEMPTS) {
            System.out.println("Too many incorrect attempts. Card retained.");
            atm.retainCard();
            atm.setState(atm.getIdleState());
        } else {
            System.out.println("Incorrect PIN. Attempts left: "
                + (MAX_ATTEMPTS - atm.getPinAttempts()));
        }
    }

    @Override public void ejectCard(ATM atm) {
        atm.ejectCardInternal();
        atm.setState(atm.getIdleState());
    }
}

final class AuthenticatedState extends AbstractATMState {
    @Override public void selectOperation(ATM atm, TransactionType type, long amount) {
        String accountId = atm.getCurrentCard().getAccountId();
        switch (type) {
            case BALANCE_INQUIRY ->
                System.out.println("Balance: " + atm.getBankService().getBalance(accountId));
            case DEPOSIT -> {
                if (amount <= 0) { System.out.println("Invalid deposit amount."); break; }
                atm.getBankService().deposit(accountId, amount);
                System.out.println("Deposited " + amount + ".");
            }
            case WITHDRAW -> atm.withdraw(accountId, amount);
        }
        // Stay authenticated: the user may run another transaction.
    }

    @Override public void ejectCard(ATM atm) {
        atm.ejectCardInternal();
        atm.setState(atm.getIdleState());
    }
}

/* ========================= Context ========================= */

public final class ATM {
    private final BankService bankService;
    private final CashDispenser cashDispenser;

    // State objects are STATELESS and reused across sessions. All
    // per-session data (card, attempts) lives in the ATM context.
    private final ATMState idleState          = new IdleState();
    private final ATMState hasCardState       = new HasCardState();
    private final ATMState authenticatedState = new AuthenticatedState();

    private ATMState currentState;
    private Card currentCard;
    private int pinAttempts;

    public ATM(BankService bankService, CashDispenser cashDispenser) {
        this.bankService  = bankService;
        this.cashDispenser = cashDispenser;
        this.currentState = idleState;
    }

    // ---- public API: pure delegation to the current state ----
    public void insertCard(Card card) { currentState.insertCard(this, card); }
    public void enterPin(String pin)  { currentState.enterPin(this, pin); }
    public void selectOperation(TransactionType t, long amt) {
        currentState.selectOperation(this, t, amt);
    }
    public void ejectCard() { currentState.ejectCard(this); }

    /**
     * Withdrawal orchestration. Order is deliberate:
     *  1) PLAN the cash (no mutation)  -> fail fast if not dispensable
     *  2) DEBIT the bank atomically    -> fail if insufficient funds
     *  3) COMMIT the cash              -> guaranteed to succeed (plan verified)
     * Debiting only AFTER we know cash can be dispensed avoids handing the
     * customer a debit with no notes.
     */
    void withdraw(String accountId, long amount) {
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
            System.out.println("Invalid withdrawal amount.");
            return;
        }
        Optional<Map<Integer, Integer>> plan = cashDispenser.tryDispense((int) amount);
        if (plan.isEmpty()) {
            System.out.println("ATM cannot dispense " + amount
                + " with available notes. Try a different amount.");
            return;
        }
        if (!bankService.withdraw(accountId, amount)) {
            System.out.println("Insufficient funds.");
            return;
        }
        cashDispenser.commit(plan.get());
        System.out.println("Please collect " + amount + ": " + plan.get());
    }

    // ---- accessors used by state classes (package-private) ----
    BankService getBankService()       { return bankService; }
    ATMState getIdleState()            { return idleState; }
    ATMState getHasCardState()         { return hasCardState; }
    ATMState getAuthenticatedState()   { return authenticatedState; }
    void setState(ATMState s)          { this.currentState = s; }
    Card getCurrentCard()              { return currentCard; }
    void setCurrentCard(Card c)        { this.currentCard = c; }
    void resetPinAttempts()            { pinAttempts = 0; }
    void incrementPinAttempts()        { pinAttempts++; }
    int getPinAttempts()               { return pinAttempts; }

    void ejectCardInternal() {
        System.out.println("Card ejected. Thank you.");
        currentCard = null;
    }
    void retainCard() {
        System.out.println("(Card moved to retention bin.)");
        currentCard = null;
    }

    /* ============================ Demo ============================ */
    public static void main(String[] args) {
        InMemoryBankService bank = new InMemoryBankService();
        bank.addAccount(new Account("ACC1", "1234", 10_000));

        // Insert in DESCENDING order — greedy planning depends on it.
        LinkedHashMap<Integer, Integer> stock = new LinkedHashMap<>();
        stock.put(2000, 5);
        stock.put(500, 10);
        stock.put(100, 20);

        ATM atm = new ATM(bank, new CashDispenser(stock));
        Card card = new Card("CARD-1", "ACC1");

        atm.insertCard(card);
        atm.enterPin("0000");                                  // wrong PIN
        atm.enterPin("1234");                                  // correct
        atm.selectOperation(TransactionType.BALANCE_INQUIRY, 0);
        atm.selectOperation(TransactionType.WITHDRAW, 3600);    // 1x2000 + 3x500 + 1x100
        atm.selectOperation(TransactionType.BALANCE_INQUIRY, 0);
        atm.selectOperation(TransactionType.WITHDRAW, 50);      // not dispensable
        atm.selectOperation(TransactionType.DEPOSIT, 2000);
        atm.selectOperation(TransactionType.BALANCE_INQUIRY, 0);
        atm.ejectCard();

        // Invalid transition: enter PIN with no card.
        try { atm.enterPin("1234"); }
        catch (IllegalStateException e) { System.out.println("Rejected: " + e.getMessage()); }
    }
}