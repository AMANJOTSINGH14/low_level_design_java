import java.util.*;
import java.time.LocalDateTime;

// Represents the possible high-level states (though the State pattern classes handle the actual logic)
enum ATMStateType { IDLE, CARD_INSERTED, AUTHENTICATED, TRANSACTION_SELECTED }

// Represents a user's bank account
class Account {
    String id; double balance; String pin;
    
    Account(String id, double balance, String pin) { 
        this.id = id; this.balance = balance; this.pin = pin; 
    }
    
    boolean verifyPin(String pin) { return this.pin.equals(pin); }
    
    // Basic withdrawal logic. Returns false if funds are insufficient.
    boolean withdraw(double amount) {
        if (amount > balance) return false;
        balance -= amount; 
        return true;
    }
    
    void deposit(double amount) { balance += amount; }
}

// Represents a transaction receipt
class Receipt {
    String transactionType; double amount; double balance; LocalDateTime timestamp;
    
    Receipt(String type, double amount, double balance) {
        this.transactionType = type; this.amount = amount;
        this.balance = balance; this.timestamp = LocalDateTime.now();
    }
    
    public String toString() {
        return transactionType + " | Amount: $" + amount + " | Balance: $" + balance + " | " + timestamp;
    }
}

// ============================================================================
// 1. CHAIN OF RESPONSIBILITY PATTERN (For dispensing cash)
// ============================================================================
// The abstract handler that all denomination dispensers will extend.
abstract class CashHandler {
    int denomination; 
    CashHandler next; // The link to the next handler in the chain
    int count; // Inventory of bills for this specific denomination
    
    CashHandler(int denom, int count) { 
        this.denomination = denom; 
        this.count = count; 
    }
    
    // Allows us to link handlers together (e.g., 100 -> 50 -> 20)
    void setNext(CashHandler next) { this.next = next; }
    
    // The core logic of the chain
    Map<Integer, Integer> dispense(int amount, Map<Integer, Integer> notes) {
        // Calculate how many bills of THIS denomination we can use
        int needed = amount / denomination;
        int used = Math.min(needed, count);
        
        // If we can use some of these bills, record it and update inventory
        if (used > 0) { 
            notes.put(denomination, used); 
            count -= used; 
        }
        
        // Calculate what is left to dispense after this handler does its part
        int remaining = amount - used * denomination;
        
        // [CHAIN BEHAVIOR] If there's still money to dispense, pass it to the next handler
        if (remaining > 0 && next != null) {
            return next.dispense(remaining, notes);
        }
        
        // If there's money remaining but no more handlers, the transaction fails
        if (remaining > 0) {
            throw new RuntimeException("Cannot dispense exact amount. Please enter a valid multiple.");
        }
        
        return notes; // Successfully dispensed
    }
}

// Concrete handlers for the chain
class Hundred extends CashHandler { Hundred(int c) { super(100, c); } }
class Fifty extends CashHandler { Fifty(int c) { super(50, c); } }
class Twenty extends CashHandler { Twenty(int c) { super(20, c); } }
class Ten extends CashHandler { Ten(int c) { super(10, c); } }


// ============================================================================
// 2. STRATEGY PATTERN (For executing different types of transactions)
// ============================================================================
// The Strategy Interface: Defines a common contract for all transactions
interface TransactionStrategy {
    Receipt execute(ATM atm, double amount);
}

// Concrete Strategy A: Handling Withdrawals
class WithdrawalStrategy implements TransactionStrategy {
    public Receipt execute(ATM atm, double amount) {
        // 1. Check if the account has enough money
        if (!atm.currentAccount.withdraw(amount)) {
            throw new RuntimeException("Insufficient funds");
        }
        
        // 2. [INTEGRATION] Trigger the Chain of Responsibility!
        // We call dispense on the head of the chain (cashHandler).
        atm.cashHandler.dispense((int) amount, new HashMap<>());
        
        // 3. Return the generated receipt
        return new Receipt("WITHDRAWAL", amount, atm.currentAccount.balance);
    }
}

// Concrete Strategy B: Handling Balance Inquiries
class BalanceInquiryStrategy implements TransactionStrategy {
    public Receipt execute(ATM atm, double amount) {
        // No money changes hands, just generate a receipt with the current balance
        return new Receipt("BALANCE_INQUIRY", 0, atm.currentAccount.balance);
    }
}


// ============================================================================
// 3. STATE PATTERN (For managing what the ATM is allowed to do at any moment)
// ============================================================================
interface ATMState {
    void insertCard(ATM atm);
    void authenticate(ATM atm, String pin);
    
    // Instead of separate withdraw/balance methods, the State accepts a Strategy
    Receipt executeTransaction(ATM atm, TransactionStrategy strategy, double amount); 
    void ejectCard(ATM atm);
}

class IdleState implements ATMState {
    public void insertCard(ATM atm) { 
        // Valid action: Move to the next state
        atm.setState(new CardInsertedState()); 
    }
    // Invalid actions for the Idle state:
    public void authenticate(ATM a, String p) { throw new RuntimeException("Insert card first"); }
    public Receipt executeTransaction(ATM a, TransactionStrategy s, double am) { throw new RuntimeException("Insert card first"); }
    public void ejectCard(ATM a) { throw new RuntimeException("No card inserted"); }
}

class CardInsertedState implements ATMState {
    public void insertCard(ATM a) { throw new RuntimeException("Card already inserted"); }
    
    public void authenticate(ATM atm, String pin) {
        if (atm.currentAccount.verifyPin(pin)) {
            // PIN is correct: Move to Authenticated state
            atm.setState(new AuthenticatedState());
        } else { 
            // PIN is wrong: Kick back to Idle state
            atm.setState(new IdleState()); 
            throw new RuntimeException("Wrong PIN"); 
        }
    }
    // Invalid actions for this state:
    public Receipt executeTransaction(ATM a, TransactionStrategy s, double am) { throw new RuntimeException("Authenticate first"); }
    public void ejectCard(ATM a) { atm.currentAccount = null; a.setState(new IdleState()); }
}

class AuthenticatedState implements ATMState {
    public void insertCard(ATM a) { throw new RuntimeException("Card already inserted"); }
    public void authenticate(ATM a, String p) { throw new RuntimeException("Already authenticated"); }
    
    // [STRATEGY IN ACTION] The state doesn't care WHAT the transaction is. 
    // It just tells the provided strategy to execute itself.
    public Receipt executeTransaction(ATM atm, TransactionStrategy strategy, double amount) {
        return strategy.execute(atm, amount);
    }
    
    public void ejectCard(ATM atm) { 
        // Clear session and return to Idle
        atm.currentAccount = null; 
        atm.setState(new IdleState()); 
    }
}


// ============================================================================
// 4. ATM / CONTEXT (Ties all patterns together; uses Singleton)
// ============================================================================
class ATM {
    // Singleton instance
    private static ATM instance;
    
    ATMState state; // Holds the current state (State Pattern)
    
    // These are public so the TransactionStrategies can access them during execution
    public Account currentAccount;  
    public CashHandler cashHandler; // The head of the Chain of Responsibility
    
    Map<String, Account> accounts = new HashMap<>();

    // Private constructor ensures no one else can create an ATM instance
    private ATM() {
        // ATM starts in the Idle state
        state = new IdleState();
        
        // [BUILDING THE CHAIN OF RESPONSIBILITY]
        // Initialize the dispensers with 100 bills each
        Hundred h = new Hundred(100); 
        Fifty f = new Fifty(100);
        Twenty t = new Twenty(100); 
        Ten te = new Ten(100);
        
        // Link them: $100 -> $50 -> $20 -> $10
        h.setNext(f); 
        f.setNext(t); 
        t.setNext(te);
        
        // The ATM only needs a reference to the top of the chain
        cashHandler = h;
    }
    
    // Standard Singleton access method
    static synchronized ATM getInstance() { 
        if (instance == null) {
            instance = new ATM(); 
        }
        return instance; 
    }
    
    // Used by State objects to change the ATM's current state
    void setState(ATMState s) { this.state = s; }
    
    // Database simulation
    void registerAccount(Account a) { accounts.put(a.id, a); }

    // --- DELEGATION METHODS ---
    // The ATM doesn't do the logic itself; it delegates to the current State
    
    void insertCard(String accountId) {
        currentAccount = accounts.get(accountId);
        if (currentAccount == null) throw new RuntimeException("Unknown account");
        state.insertCard(this);
    }
    
    void authenticate(String pin) { 
        state.authenticate(this, pin); 
    }
    
    // [STRATEGY DELEGATION] The client passes the strategy they want to use
    Receipt executeTransaction(TransactionStrategy strategy, double amount) {
        return state.executeTransaction(this, strategy, amount);
    }
    
    void ejectCard() { 
        state.ejectCard(this); 
    }
}

// ============================================================================
// USAGE EXAMPLE
// ============================================================================
/*
public class Main {
    public static void main(String[] args) {
        // 1. Get Singleton ATM instance
        ATM atm = ATM.getInstance();
        
        // 2. Setup mock data
        atm.registerAccount(new Account("A1", 5000, "1234"));
        
        // 3. State transitions
        atm.insertCard("A1"); // State changes to CardInserted
        atm.authenticate("1234"); // State changes to Authenticated
        
        // 4. Execute a Withdrawal using the Strategy Pattern
        // This will internally trigger the Chain of Responsibility to dispense cash
        Receipt r1 = atm.executeTransaction(new WithdrawalStrategy(), 270);
        System.out.println(r1);
        
        // 5. Execute a Balance Inquiry using a different Strategy
        Receipt r2 = atm.executeTransaction(new BalanceInquiryStrategy(), 0);
        System.out.println(r2);
        
        // 6. End session
        atm.ejectCard(); // State changes back to Idle
    }
}
*/