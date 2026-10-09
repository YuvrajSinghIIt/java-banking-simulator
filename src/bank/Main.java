package bank;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Demo: 20 threads x 500 random transfers, conservation check, then crash-recovery check. */
public class Main {
    static void worker(Bank bank, int transfers, long seed) {
        Random rnd = new Random(seed);
        for (int i = 0; i < transfers; i++) {
            int from = 1 + rnd.nextInt(5);
            int to = 1 + rnd.nextInt(5);
            long amount = 100 + rnd.nextInt(4901); // Rs 1 to Rs 50
            bank.transfer(from, to, amount);
        }
    }

    public static void main(String[] args) throws Exception {
        Path log = Files.createTempFile("bank-wal", ".log");
        Files.deleteIfExists(log);

        long[] finalBalances = new long[6];
        long initialTotal, finalTotal;
        try (Bank bank = new Bank(log)) {
            for (int i = 1; i <= 5; i++) {
                if (i % 2 == 1) bank.createSavings(i, "User" + i, 100_000, 10_000);   // min Rs 100
                else            bank.createCurrent(i, "User" + i, 100_000, 50_000);   // overdraft Rs 500
            }
            initialTotal = bank.getTotalBankBalance();

            final int THREADS = 20, PER_THREAD = 500;
            List<Thread> threads = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < THREADS; i++) {
                final long seed = 12345L + i;
                Thread t = new Thread(() -> worker(bank, PER_THREAD, seed));
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) t.join();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            finalTotal = bank.getTotalBankBalance();
            for (int i = 1; i <= 5; i++) finalBalances[i] = bank.getAccount(i).getBalance();
            System.out.println("Initial total: " + initialTotal + " paise");
            System.out.println("Final total:   " + finalTotal + " paise");
            System.out.println("Attempted transfers: " + THREADS * PER_THREAD + " (" + ms + " ms, fsync per transfer)");
            System.out.println(initialTotal == finalTotal ? "PASS: Balance conservation check."
                                                          : "FAIL: Balance conservation check.");
        } // bank closed WITHOUT any special shutdown step: all state lives only in the log now

        // "Crash recovery": a brand-new Bank object rebuilds everything from the log file.
        boolean same = true;
        try (Bank recovered = new Bank(log)) {
            for (int i = 1; i <= 5; i++) {
                long b = recovered.getAccount(i).getBalance();
                if (b != finalBalances[i]) same = false;
            }
            System.out.println("Recovered total: " + recovered.getTotalBankBalance() + " paise");
        }
        System.out.println(same ? "PASS: Recovery reproduced every account balance."
                                : "FAIL: Recovery mismatch.");
        Files.deleteIfExists(log);
        if (initialTotal != finalTotal || !same) System.exit(1);
    }
}
