package bank;

/** Current account: balance may go negative, but not beyond the overdraft limit. */
public class CurrentAccount extends Account {
    private final long overdraftLimit;

    public CurrentAccount(int id, String name, long initialBalance, long overdraftLimit) {
        super(id, name, initialBalance);
        if (overdraftLimit < 0) throw new IllegalArgumentException("Negative overdraft limit");
        this.overdraftLimit = overdraftLimit;
    }

    public long getOverdraftLimit() { return overdraftLimit; }

    @Override protected long lowestAllowedBalance() { return -overdraftLimit; }
    @Override public String type() { return "CURRENT"; }
}
