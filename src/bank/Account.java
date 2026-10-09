package bank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Base class for all accounts. Money is stored as a long in paise (100 paise = Rs 1)
 * so there are no floating-point rounding errors.
 *
 * Subclasses decide how far the balance may drop (polymorphism): see
 * {@link #lowestAllowedBalance()}.
 *
 * Every account owns one ReentrantLock. Methods ending in "Locked" assume the caller
 * already holds that lock; Bank.transfer() uses them after taking BOTH locks in id order.
 */
public abstract class Account {
    private final int id;
    private final String name;
    private long balance;
    private final List<String> history = new ArrayList<>();
    final ReentrantLock lock = new ReentrantLock();

    protected Account(int id, String name, long initialBalance) {
        if (id <= 0 || name == null || name.isBlank() || initialBalance < 0) {
            throw new IllegalArgumentException("Invalid account details");
        }
        this.id = id;
        this.name = name;
        this.balance = initialBalance;
    }

    /** The lowest balance this account type may reach after a withdrawal. */
    protected abstract long lowestAllowedBalance();

    /** Short label used in the log file and in toString. */
    public abstract String type();

    public int getId() { return id; }
    public String getName() { return name; }

    // ---------- public read API (takes the lock itself) ----------
    // Mutations are deliberately NOT public: they must go through Bank so every change
    // is written to the write-ahead log first.

    public long getBalance() {
        lock.lock();
        try { return balance; } finally { lock.unlock(); }
    }

    public List<String> getHistory() {
        lock.lock();
        try { return new ArrayList<>(history); } finally { lock.unlock(); }
    }

    // ---------- "Locked" helpers: caller must hold this.lock ----------

    boolean canWithdrawLocked(long amount) {
        // Need: balance - amount >= lowestAllowedBalance, i.e. amount <= balance - lowest.
        // 'lowest' can be negative (overdraft), so balance - lowest can overflow upward.
        if (amount <= 0) return false;
        long available;
        try {
            available = Math.subtractExact(balance, lowestAllowedBalance());
        } catch (ArithmeticException overflow) {
            available = Long.MAX_VALUE; // more room than any long amount can use
        }
        return amount <= available;
    }

    boolean canDepositLocked(long amount) {
        // Long.MAX_VALUE - balance overflows when balance is negative (overdrawn account),
        // but then any positive amount is safe to add.
        return amount > 0 && (balance <= 0 || amount <= Long.MAX_VALUE - balance);
    }

    void applyWithdrawLocked(long amount, String note) {
        history.add(note + amount + " paise");
        balance -= amount;
    }

    void applyDepositLocked(long amount, String note) {
        history.add(note + amount + " paise");
        balance += amount;
    }

    long balanceLocked() { return balance; }

    @Override
    public String toString() {
        return type() + "#" + id + "(" + name + ")";
    }
}
