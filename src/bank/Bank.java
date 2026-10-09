package bank;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe bank with a write-ahead log.
 *
 * Concurrency design
 *  - One ReentrantLock per account (fine-grained locking).
 *  - transfer() locks BOTH accounts, always the LOWER account id first. Because every
 *    thread uses the same global order, a circular wait (deadlock) cannot form.
 *
 * Durability design
 *  - For every change: take the account lock(s) -> validate -> append to the WAL and
 *    force() -> only then change balances. If append fails, nothing changes.
 *  - Because the log append happens while the account locks are held, the order of
 *    records in the log matches the order in which changes touch each account, so
 *    replaying the log reproduces exactly the same balances.
 *  - On startup the log is replayed to rebuild every account and transaction.
 */
public final class Bank implements AutoCloseable {
    private static final byte OP_CREATE = 1, OP_DEPOSIT = 2, OP_WITHDRAW = 3, OP_TRANSFER = 4;
    private static final byte KIND_SAVINGS = 0, KIND_CURRENT = 1;

    private final ConcurrentHashMap<Integer, Account> accounts = new ConcurrentHashMap<>();
    private final WriteAheadLog wal; // null => in-memory only (no durability)
    private boolean recovering = false;

    /** In-memory bank, no log file. */
    public Bank() { this.wal = null; }

    /** Bank backed by a log file; replays the log to restore previous state. */
    public Bank(Path logFile) throws IOException {
        this.wal = new WriteAheadLog(logFile);
        this.recovering = true;
        wal.recover(this::replay);
        this.recovering = false;
    }

    // ------------------------------------------------------------------ accounts

    public synchronized boolean createSavings(int id, String name, long initial, long minBalance) {
        if (id <= 0 || name == null || name.isBlank() || initial < 0 || minBalance < 0
                || initial < minBalance || accounts.containsKey(id)) return false;
        log(payload(OP_CREATE, KIND_SAVINGS, id, name, initial, minBalance));
        accounts.put(id, new SavingsAccount(id, name, initial, minBalance));
        return true;
    }

    public synchronized boolean createCurrent(int id, String name, long initial, long overdraftLimit) {
        if (id <= 0 || name == null || name.isBlank() || initial < 0 || overdraftLimit < 0
                || accounts.containsKey(id)) return false;
        log(payload(OP_CREATE, KIND_CURRENT, id, name, initial, overdraftLimit));
        accounts.put(id, new CurrentAccount(id, name, initial, overdraftLimit));
        return true;
    }

    public Account getAccount(int id) { return accounts.get(id); }

    // -------------------------------------------------------------- transactions

    public boolean deposit(int id, long amount) {
        Account a = accounts.get(id);
        if (a == null) return false;
        a.lock.lock();
        try {
            if (!a.canDepositLocked(amount)) return false;
            log(payload(OP_DEPOSIT, id, amount));
            a.applyDepositLocked(amount, "Deposited: ");
            return true;
        } finally {
            a.lock.unlock();
        }
    }

    public boolean withdraw(int id, long amount) {
        Account a = accounts.get(id);
        if (a == null) return false;
        a.lock.lock();
        try {
            if (!a.canWithdrawLocked(amount)) return false; // polymorphic rule per account type
            log(payload(OP_WITHDRAW, id, amount));
            a.applyWithdrawLocked(amount, "Withdrew: ");
            return true;
        } finally {
            a.lock.unlock();
        }
    }

    public boolean transfer(int fromId, int toId, long amount) {
        if (amount <= 0 || fromId == toId) return false;
        Account from = accounts.get(fromId);
        Account to = accounts.get(toId);
        if (from == null || to == null) return false;

        // Deadlock avoidance: always lock the account with the smaller id first.
        Account first = fromId < toId ? from : to;
        Account second = fromId < toId ? to : from;
        first.lock.lock();
        try {
            second.lock.lock();
            try {
                if (!from.canWithdrawLocked(amount) || !to.canDepositLocked(amount)) return false;
                log(payload(OP_TRANSFER, fromId, toId, amount));
                from.applyWithdrawLocked(amount, "Transferred out: ");
                to.applyDepositLocked(amount, "Transferred in: ");
                return true;
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    /**
     * Sum of all balances. Only meaningful when no transactions are running (before
     * workers start or after they all finish); it does not take a consistent snapshot.
     */
    public long getTotalBankBalance() {
        long total = 0;
        for (Account a : accounts.values()) {
            long b = a.getBalance();
            try {
                total = Math.addExact(total, b);
            } catch (ArithmeticException e) {
                throw new ArithmeticException("Total balance is too large");
            }
        }
        return total;
    }

    // ----------------------------------------------------------------- WAL plumbing

    private void log(byte[] record) {
        if (wal == null || recovering) return;
        try {
            wal.append(record);
        } catch (IOException e) {
            throw new UncheckedIOException("Write-ahead log failure; transaction aborted", e);
        }
    }

    private static byte[] payload(Object... fields) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            for (Object f : fields) {
                if (f instanceof Byte b) out.writeByte(b);
                else if (f instanceof Integer i) out.writeInt(i);
                else if (f instanceof Long l) out.writeLong(l);
                else if (f instanceof String s) out.writeUTF(s);
                else throw new IllegalArgumentException("Unsupported field type");
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Re-applies one logged record (called only during recovery, single-threaded). */
    private void replay(byte[] record) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(record))) {
            byte op = in.readByte();
            boolean ok;
            switch (op) {
                case OP_CREATE -> {
                    byte kind = in.readByte();
                    int id = in.readInt();
                    String name = in.readUTF();
                    long initial = in.readLong();
                    long param = in.readLong();
                    ok = kind == KIND_SAVINGS ? createSavings(id, name, initial, param)
                                              : createCurrent(id, name, initial, param);
                }
                case OP_DEPOSIT -> ok = deposit(in.readInt(), in.readLong());
                case OP_WITHDRAW -> ok = withdraw(in.readInt(), in.readLong());
                case OP_TRANSFER -> ok = transfer(in.readInt(), in.readInt(), in.readLong());
                default -> throw new IllegalStateException("Unknown log record type " + op);
            }
            if (!ok) throw new IllegalStateException("Log record could not be re-applied");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (wal != null) wal.close();
    }
}
