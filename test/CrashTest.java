import bank.*;
import java.nio.file.Path;
import java.util.Random;

/** Usage: CrashTest write <log>  (runs until killed)   |   CrashTest verify <log> */
public class CrashTest {
    static final long START_TOTAL = 5 * 100_000L;

    public static void main(String[] args) throws Exception {
        Path log = Path.of(args[1]);
        if (args[0].equals("write")) {
            Bank b = new Bank(log);
            if (b.getAccount(1) == null) {
                for (int i = 1; i <= 5; i++) {
                    if (i % 2 == 1) b.createSavings(i, "U" + i, 100_000, 10_000);
                    else b.createCurrent(i, "U" + i, 100_000, 50_000);
                }
            }
            for (int t = 0; t < 4; t++) {
                final int seed = t;
                new Thread(() -> {
                    Random r = new Random(seed);
                    while (true) b.transfer(1 + r.nextInt(5), 1 + r.nextInt(5), 100 + r.nextInt(4900));
                }).start();
            }
            System.out.println("writing... (kill me)");
        } else {
            try (Bank b = new Bank(log)) {
                long total = b.getTotalBankBalance();
                boolean ok = total == START_TOTAL;
                for (int i = 1; i <= 5; i++) {
                    Account a = b.getAccount(i);
                    System.out.println(a + " balance=" + a.getBalance() + " history=" + a.getHistory().size());
                }
                System.out.println("total after crash recovery = " + total + (ok ? "  PASS" : "  FAIL"));
                System.exit(ok ? 0 : 1);
            }
        }
    }
}
