import bank.*;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests (no JUnit needed). Run with: make test */
public class Tests {
    static int checks = 0;
    static void check(boolean cond, String what) {
        checks++;
        if (!cond) { System.err.println("FAIL: " + what); System.exit(1); }
    }

    static Path tmp() throws Exception {
        Path p = Files.createTempFile("wal", ".log");
        Files.deleteIfExists(p);
        return p;
    }

    public static void main(String[] args) throws Exception {
        validationAndPolymorphism();
        overdrawnAccountsAcceptDeposits();
        overflow();
        concurrentOpposingTransfers();
        recoveryRebuildsState();
        recoveryIgnoresTornTail();
        recoveryIgnoresCorruptRecord();
        System.out.println("PASS: all " + checks + " checks.");
    }

    static void validationAndPolymorphism() {
        Bank b = new Bank();
        check(!b.createSavings(0, "A", 0, 0), "id must be positive");
        check(!b.createSavings(1, "  ", 0, 0), "blank name rejected");
        check(!b.createSavings(1, "A", 50, 100), "initial below minimum rejected");
        check(b.createSavings(1, "A", 1000, 200), "savings created");
        check(b.createCurrent(2, "B", 500, 300), "current created");
        check(!b.createCurrent(1, "Dup", 0, 0), "duplicate id rejected");
        check(b.getAccount(9) == null, "missing account is null");

        // Savings must keep minimum balance 200: can withdraw at most 800.
        check(!b.withdraw(1, 801), "savings cannot go below minimum");
        check(b.withdraw(1, 800), "savings may reach exactly the minimum");
        // Current may overdraw up to 300 below zero: can withdraw at most 800 from 500.
        check(!b.withdraw(2, 801), "current cannot exceed overdraft");
        check(b.withdraw(2, 800), "current may use overdraft");
        check(b.getAccount(2).getBalance() == -300, "overdrawn balance is -300");

        check(!b.deposit(1, 0) && !b.deposit(1, -5), "non-positive deposit rejected");
        check(!b.transfer(1, 1, 1), "self-transfer rejected");
        check(!b.transfer(1, 9, 1) && !b.transfer(9, 1, 1), "missing account transfer rejected");
        check(b.transfer(2, 1, 0) == false, "zero transfer rejected");
        List<String> h = b.getAccount(1).getHistory();
        h.clear();
        check(b.getAccount(1).getHistory().size() == 1, "history getter returns a copy");
    }

    static void overdrawnAccountsAcceptDeposits() {
        Bank b = new Bank();
        b.createCurrent(1, "A", 0, 1_000);
        b.createCurrent(2, "B", 5_000, 0);
        check(b.withdraw(1, 800), "overdraw account 1 to -800");
        check(b.deposit(1, 300), "deposit into overdrawn account works");
        check(b.getAccount(1).getBalance() == -500, "balance -500 after deposit");
        check(b.transfer(2, 1, 1_000), "transfer into overdrawn account works");
        check(b.getAccount(1).getBalance() == 500, "balance back to positive");
        // huge balance + overdraft must not trip the subtraction overflow
        Bank big = new Bank();
        big.createCurrent(1, "Big", Long.MAX_VALUE, 1_000);
        check(big.withdraw(1, 5), "withdraw from near-MAX balance with overdraft");
        check(big.getAccount(1).getBalance() == Long.MAX_VALUE - 5, "near-MAX balance updated");
    }

    static void overflow() {
        Bank b = new Bank();
        b.createCurrent(1, "A", Long.MAX_VALUE, 0);
        b.createCurrent(2, "B", 1, 0);
        check(!b.deposit(1, 1), "deposit overflow rejected");
        check(!b.transfer(2, 1, 1), "transfer overflow rejected");
        boolean caught = false;
        try { b.getTotalBankBalance(); } catch (ArithmeticException e) { caught = true; }
        check(caught, "total overflow detected");
    }

    static void concurrentOpposingTransfers() throws Exception {
        Bank b = new Bank();
        b.createCurrent(1, "A", 100_000, 0);
        b.createCurrent(2, "B", 100_000, 0);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int dir = i % 2;
            Thread t = new Thread(() -> {
                for (int j = 0; j < 2000; j++) {
                    if (!b.transfer(dir + 1, 2 - dir, 1)) { System.err.println("FAIL: transfer rejected"); System.exit(1); }
                }
            });
            ts.add(t); t.start();
        }
        for (Thread t : ts) t.join(); // would hang here if lock ordering were wrong
        check(b.getTotalBankBalance() == 200_000, "total conserved");
        check(b.getAccount(1).getBalance() == 100_000, "account 1 net zero");
        check(b.getAccount(2).getBalance() == 100_000, "account 2 net zero");
        check(b.getAccount(1).getHistory().size() == 16_000, "account 1 history count (8000 out + 8000 in)");
    }

    static void recoveryRebuildsState() throws Exception {
        Path log = tmp();
        try (Bank b = new Bank(log)) {
            b.createSavings(1, "A", 10_000, 1_000);
            b.createCurrent(2, "B", 5_000, 2_000);
            b.deposit(1, 500);
            b.withdraw(2, 6_000);          // uses overdraft
            b.transfer(1, 2, 2_500);
            b.transfer(1, 2, 999_999);     // rejected -> must NOT be logged
        }
        try (Bank r = new Bank(log)) {
            check(r.getAccount(1).getBalance() == 8_000, "recovered balance 1");
            check(r.getAccount(2).getBalance() == 1_500, "recovered balance 2 (after overdraft)");
            check(r.getAccount(1) instanceof SavingsAccount, "recovered type savings");
            check(r.getAccount(2) instanceof CurrentAccount, "recovered type current");
            check(r.getAccount(1).getHistory().size() == 2, "recovered history");
            check(r.deposit(1, 1), "recovered bank still works");
        }
        // recover again (new record appended after recovery must also survive)
        try (Bank r2 = new Bank(log)) {
            check(r2.getAccount(1).getBalance() == 8_001, "second recovery sees post-recovery write");
        }
        Files.deleteIfExists(log);
    }

    static void recoveryIgnoresTornTail() throws Exception {
        Path log = tmp();
        try (Bank b = new Bank(log)) {
            b.createCurrent(1, "A", 1_000, 0);
            b.deposit(1, 100);
        }
        long goodSize = Files.size(log);
        try (Bank b = new Bank(log)) { b.deposit(1, 50); } // 3rd record
        // Simulate a crash in the middle of writing that last record: chop 3 bytes off.
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "rw")) { f.setLength(Files.size(log) - 3); }
        try (Bank r = new Bank(log)) {
            check(r.getAccount(1).getBalance() == 1_100, "torn last record ignored");
            check(Files.size(log) == goodSize, "log truncated back to last good record");
        }
        Files.deleteIfExists(log);
    }

    static void recoveryIgnoresCorruptRecord() throws Exception {
        Path log = tmp();
        try (Bank b = new Bank(log)) {
            b.createCurrent(1, "A", 1_000, 0);
            b.deposit(1, 100);
            b.deposit(1, 200);
        }
        // Flip a byte inside the LAST record's payload so its CRC no longer matches.
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "rw")) {
            long end = f.length();
            f.seek(end - 1);
            int v = f.read();
            f.seek(end - 1);
            f.write(v ^ 0xFF);
        }
        try (Bank r = new Bank(log)) {
            check(r.getAccount(1).getBalance() == 1_100, "corrupt last record dropped");
        }
        Files.deleteIfExists(log);
    }
}
