package bank;

/** Savings account: must always keep a minimum balance. */
public class SavingsAccount extends Account {
    private final long minimumBalance;

    public SavingsAccount(int id, String name, long initialBalance, long minimumBalance) {
        super(id, name, initialBalance);
        if (minimumBalance < 0 || initialBalance < minimumBalance) {
            throw new IllegalArgumentException("Initial balance is below the minimum balance");
        }
        this.minimumBalance = minimumBalance;
    }

    public long getMinimumBalance() { return minimumBalance; }

    @Override protected long lowestAllowedBalance() { return minimumBalance; }
    @Override public String type() { return "SAVINGS"; }
}
