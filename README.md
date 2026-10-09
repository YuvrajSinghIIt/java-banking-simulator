# Java Banking Transaction Simulator

A concurrency- and durability-focused banking simulator built by [Yuvraj Singh](https://github.com/YuvrajSinghIIt). It models savings and current accounts, applies transfers safely across concurrent threads, and persists every successful state change through a write-ahead log (WAL).

This is a Java 17, standard-library-only learning project—there are no frameworks or external dependencies.

## Highlights

- **Polymorphic account model:** an abstract `Account` base class supports `SavingsAccount` minimum-balance rules and `CurrentAccount` overdraft rules.
- **Thread-safe transfers:** each account owns a `ReentrantLock`; transfers acquire locks in account-ID order to avoid circular wait and deadlock.
- **Write-ahead logging:** records are appended and forced to disk before in-memory balances change.
- **Crash recovery:** startup replays valid WAL records and drops a torn or corrupt final record using length and CRC32 checks.
- **Integer money:** balances are stored as `long` paise to avoid floating-point rounding errors, with overflow-safe arithmetic.
- **Regression checks:** 41 dependency-free tests cover validation, polymorphism, overflow, concurrent transfers, recovery, and damaged log tails.

## Transaction flow

```mermaid
flowchart LR
    R[Transaction request] --> L[Acquire account lock(s)]
    L --> V{Valid operation?}
    V -->|No| X[Reject without changing state]
    V -->|Yes| W[Append WAL record]
    W --> F[Force WAL to disk]
    F --> M[Apply balance change]
    M --> S[Return success]
```

By writing and forcing the WAL record before mutating a balance, the simulator can reconstruct committed state after a process crash.

## Requirements

- JDK 17 or newer
- GNU Make (optional, used by the quick commands below)

Check your installation:

```bash
java --version
javac --version
```

## Build and run

From the project root:

```bash
make run
```

The demo creates five accounts, runs 20 concurrent threads with 500 transfer attempts each, verifies conservation of total funds, then reconstructs the bank from the WAL.

## Test

```bash
make test
```

Run the crash-recovery demonstration on macOS or Linux:

```bash
make crash
```

This starts a writer process, terminates it with `kill -9`, and opens the log again to confirm that the total balance is recovered. It is a process-crash test, not a substitute for power-loss testing.

Without Make:

```bash
mkdir -p build
javac -Xlint:all -d build src/bank/*.java test/*.java
java -cp build bank.Main
java -cp build Tests
```

## Project structure

```text
.
├── src/bank/
│   ├── Account.java          # shared account state and locking
│   ├── SavingsAccount.java   # minimum-balance account
│   ├── CurrentAccount.java   # overdraft-enabled account
│   ├── Bank.java             # account operations and transaction coordination
│   ├── WriteAheadLog.java    # durable append/replay with CRC32 verification
│   └── Main.java             # concurrent transaction and recovery demo
├── test/
│   ├── Tests.java            # dependency-free regression checks
│   └── CrashTest.java        # process-crash recovery demonstration
└── Makefile                  # build, run, test, crash, and clean shortcuts
```

## Key design choices

| Concern | Approach |
| --- | --- |
| Money | `long` paise, with checked additions and subtractions |
| Concurrency | One lock per account; transfers lock lower ID first |
| Atomicity | Validate → append WAL → force WAL → update balances |
| Recovery | Replay complete, CRC-valid records in order |
| Invalid operations | Rejected before logging or mutating state |

## Known limitations

- `getTotalBankBalance()` is not a consistent snapshot while transfers are still running.
- The WAL is append-only: it is not checkpointed or compacted, so recovery time grows with the log.
- A successful `FileChannel.force(true)` requests durability from the operating system; actual power-loss guarantees depend on the storage stack.
- One forced log write occurs per transaction while the relevant account locks are held. This favors durability and simplicity over throughput.

## License

Released under the [ISC License](LICENSE).
