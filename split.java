import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class SplitwiseDemo {

    enum SplitType { EQUAL, EXACT, PERCENT }

    /* ---------------- Domain entities ---------------- */
    static final class User {
        private final String id, name;
        User(String id, String name) { this.id = id; this.name = name; }
        String getId() { return id; }
        String getName() { return name; }
    }

    // A Split = how much ONE user owes for ONE expense.
    static final class Split {
        private final User user;
        private final double amount;
        Split(User user, double amount) { this.user = user; this.amount = amount; }
        User getUser() { return user; }
        double getAmount() { return amount; }
    }

    static final class Expense {
        private final String id, description;
        private final double totalAmount;
        private final User paidBy;
        private final List<Split> splits;
        Expense(String id, String description, double total, User paidBy, List<Split> splits) {
            this.id = id; this.description = description;
            this.totalAmount = total; this.paidBy = paidBy; this.splits = splits;
        }
        @Override public String toString() {
            return id + " '" + description + "' $" + totalAmount + " paid by " + paidBy.getName();
        }
    }

    /* ---------------- Money helpers ----------------
     * All math is done in integer cents so splits reconcile exactly.
     * In production I'd use BigDecimal or a Money type; cents keeps the demo small. */
    static long toCents(double amount) { return Math.round(amount * 100); }
    static double toDollars(long cents) { return cents / 100.0; }

    // Split a cent amount into n parts that sum EXACTLY to the original.
    static long[] splitCents(long totalCents, int n) {
        long base = totalCents / n, remainder = totalCents % n;
        long[] parts = new long[n];
        for (int i = 0; i < n; i++) parts[i] = base + (i < remainder ? 1 : 0);
        return parts; // first `remainder` people absorb the leftover cents
    }

    /* ---------------- Strategy ---------------- */
    interface SplitStrategy {
        // `metadata` meaning: ignored for EQUAL, exact amounts for EXACT, percentages for PERCENT.
        List<Split> calculateSplits(double total, User paidBy,
                                    List<User> participants, List<Double> metadata);
    }

    static final class EqualSplitStrategy implements SplitStrategy {
        public List<Split> calculateSplits(double total, User paidBy,
                                           List<User> participants, List<Double> metadata) {
            long[] parts = splitCents(toCents(total), participants.size());
            List<Split> splits = new ArrayList<>();
            for (int i = 0; i < participants.size(); i++)
                splits.add(new Split(participants.get(i), toDollars(parts[i])));
            return splits;
        }
    }

    static final class ExactSplitStrategy implements SplitStrategy {
        public List<Split> calculateSplits(double total, User paidBy,
                                           List<User> participants, List<Double> exact) {
            if (exact == null || exact.size() != participants.size())
                throw new IllegalArgumentException("One exact amount required per participant");
            long sum = 0;
            List<Split> splits = new ArrayList<>();
            for (int i = 0; i < participants.size(); i++) {
                long c = toCents(exact.get(i));
                if (c < 0) throw new IllegalArgumentException("Split amount cannot be negative");
                sum += c;
                splits.add(new Split(participants.get(i), toDollars(c)));
            }
            if (sum != toCents(total))
                throw new IllegalArgumentException("Exact splits must sum to the total");
            return splits;
        }
    }

    static final class PercentSplitStrategy implements SplitStrategy {
        public List<Split> calculateSplits(double total, User paidBy,
                                           List<User> participants, List<Double> pct) {
            if (pct == null || pct.size() != participants.size())
                throw new IllegalArgumentException("One percentage required per participant");
            double pctSum = 0;
            for (double p : pct) {
                if (p < 0) throw new IllegalArgumentException("Percentage cannot be negative");
                pctSum += p;
            }
            if (Math.abs(pctSum - 100.0) > 1e-6)
                throw new IllegalArgumentException("Percentages must sum to 100");
            long totalCents = toCents(total), allocated = 0;
            long[] parts = new long[participants.size()];
            for (int i = 0; i < participants.size(); i++) {
                parts[i] = Math.round(totalCents * pct.get(i) / 100.0);
                allocated += parts[i];
            }
            parts[0] += (totalCents - allocated); // absorb rounding drift so parts sum exactly
            List<Split> splits = new ArrayList<>();
            for (int i = 0; i < participants.size(); i++)
                splits.add(new Split(participants.get(i), toDollars(parts[i])));
            return splits;
        }
    }

    /* ---------------- Factory (strategies are stateless => shared) ---------------- */
    static final class SplitStrategyFactory {
        private static final Map<SplitType, SplitStrategy> STRATEGIES = new EnumMap<>(SplitType.class);
        static {
            STRATEGIES.put(SplitType.EQUAL, new EqualSplitStrategy());
            STRATEGIES.put(SplitType.EXACT, new ExactSplitStrategy());
            STRATEGIES.put(SplitType.PERCENT, new PercentSplitStrategy());
        }
        static SplitStrategy get(SplitType type) {
            SplitStrategy s = STRATEGIES.get(type);
            if (s == null) throw new IllegalArgumentException("Unknown split type: " + type);
            return s;
        }
    }

    /* ---------------- Balance sheet ----------------
     * balances[A][B] > 0  =>  A owes B that amount. Both directions kept in sync. */
    static final class BalanceSheet {
        private final Map<String, Map<String, Double>> balances = new HashMap<>();

        void transfer(User debtor, User creditor, double amount) {
            if (debtor.getId().equals(creditor.getId())) return;
            adjust(debtor.getId(), creditor.getId(), amount);
            adjust(creditor.getId(), debtor.getId(), -amount);
        }
        private void adjust(String from, String to, double delta) {
            balances.computeIfAbsent(from, k -> new HashMap<>()).merge(to, delta, Double::sum);
        }
        Map<String, Double> balancesFor(String userId) {
            return balances.getOrDefault(userId, Collections.emptyMap());
        }
        Map<String, Map<String, Double>> all() { return balances; }
    }

    /* ---------------- Service (facade) ---------------- */
    static final class SplitwiseService {
        private final Map<String, User> users = new HashMap<>();
        private final List<Expense> expenses = new ArrayList<>();
        private final BalanceSheet balanceSheet = new BalanceSheet();
        private final ReentrantLock lock = new ReentrantLock(); // guards balance read-modify-write
        private int expenseSeq = 0;

        User addUser(String id, String name) {
            User u = new User(id, name);
            users.put(id, u);
            return u;
        }

        Expense addExpense(String description, double total, User paidBy,
                           List<User> participants, SplitType type, List<Double> metadata) {
            if (total <= 0) throw new IllegalArgumentException("Amount must be positive");
            if (participants == null || participants.isEmpty())
                throw new IllegalArgumentException("Need at least one participant");
            // Generic validation lives here; type-specific validation lives in the strategy.
            Set<String> seen = new HashSet<>();
            for (User p : participants)
                if (!seen.add(p.getId()))
                    throw new IllegalArgumentException("Duplicate participant: " + p.getName());

            SplitStrategy strategy = SplitStrategyFactory.get(type);
            List<Split> splits = strategy.calculateSplits(total, paidBy, participants, metadata);

            lock.lock();
            try {
                Expense e = new Expense("E" + (++expenseSeq), description, total, paidBy, splits);
                // Every participant owes the payer their share (skip the payer's own share).
                for (Split s : splits)
                    if (!s.getUser().getId().equals(paidBy.getId()))
                        balanceSheet.transfer(s.getUser(), paidBy, s.getAmount());
                expenses.add(e);
                return e;
            } finally {
                lock.unlock();
            }
        }

        void showBalance(User user) {
            lock.lock();
            try {
                Map<String, Double> bal = balanceSheet.balancesFor(user.getId());
                boolean any = false;
                for (Map.Entry<String, Double> e : bal.entrySet()) {
                    double amt = e.getValue();
                    if (Math.abs(amt) < 0.005) continue; // settled (money is in whole cents)
                    any = true;
                    String other = users.get(e.getKey()).getName();
                    if (amt > 0)
                        System.out.printf("  %s owes %s $%.2f%n", user.getName(), other, amt);
                    else
                        System.out.printf("  %s gets back $%.2f from %s%n", user.getName(), -amt, other);
                }
                if (!any) System.out.println("  " + user.getName() + " is all settled up");
            } finally {
                lock.unlock();
            }
        }

        /* Greedy debt simplification: minimizes the transaction count in practice
         * (true minimum is NP-hard). Net balances are always preserved. */
        List<String> simplifyDebts() {
            lock.lock();
            try {
                Map<String, Double> net = new HashMap<>();
                for (Map.Entry<String, Map<String, Double>> row : balanceSheet.all().entrySet()) {
                    double sum = 0;
                    for (double v : row.getValue().values()) sum += v;
                    net.merge(row.getKey(), sum, Double::sum); // sum>0 => net debtor
                }
                PriorityQueue<Map.Entry<String, Double>> debtors =   // max-heap by amount owed
                    new PriorityQueue<>((a, b) -> Double.compare(b.getValue(), a.getValue()));
                PriorityQueue<Map.Entry<String, Double>> creditors = // min-heap (most negative first)
                    new PriorityQueue<>((a, b) -> Double.compare(a.getValue(), b.getValue()));
                for (Map.Entry<String, Double> e : net.entrySet()) {
                    if (e.getValue() > 0.005)       debtors.add(Map.entry(e.getKey(), e.getValue()));
                    else if (e.getValue() < -0.005) creditors.add(Map.entry(e.getKey(), e.getValue()));
                }
                List<String> settlements = new ArrayList<>();
                while (!debtors.isEmpty() && !creditors.isEmpty()) {
                    Map.Entry<String, Double> d = debtors.poll();
                    Map.Entry<String, Double> c = creditors.poll();
                    double settled = Math.min(d.getValue(), -c.getValue());
                    settlements.add(String.format("%s pays %s $%.2f",
                        users.get(d.getKey()).getName(), users.get(c.getKey()).getName(), settled));
                    double dRem = d.getValue() - settled, cRem = c.getValue() + settled;
                    if (dRem > 0.005)  debtors.add(Map.entry(d.getKey(), dRem));
                    if (cRem < -0.005) creditors.add(Map.entry(c.getKey(), cRem));
                }
                return settlements;
            } finally {
                lock.unlock();
            }
        }
    }

    /* ---------------- Demo ---------------- */
    public static void main(String[] args) {
        SplitwiseService service = new SplitwiseService();
        User alice = service.addUser("u1", "Alice");
        User bob   = service.addUser("u2", "Bob");
        User carol = service.addUser("u3", "Carol");

        // Equal: Alice pays $90 dinner for all three.
        service.addExpense("Dinner", 90.0, alice,
                Arrays.asList(alice, bob, carol), SplitType.EQUAL, null);
        // Exact: Bob pays $50 cab => Alice 20, Bob 10, Carol 20.
        service.addExpense("Cab", 50.0, bob,
                Arrays.asList(alice, bob, carol), SplitType.EXACT, Arrays.asList(20.0, 10.0, 20.0));
        // Percent: Carol pays $100 hotel => Alice 50%, Bob 30%, Carol 20%.
        service.addExpense("Hotel", 100.0, carol,
                Arrays.asList(alice, bob, carol), SplitType.PERCENT, Arrays.asList(50.0, 30.0, 20.0));

        System.out.println("=== Balances ===");
        for (User u : Arrays.asList(alice, bob, carol)) service.showBalance(u);

        System.out.println("=== Simplified settlement ===");
        for (String s : service.simplifyDebts()) System.out.println("  " + s);
    }
}