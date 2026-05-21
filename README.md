# Banking Ledger OLTP

A small Java OLTP-style banking ledger built step by step.

The first milestone is an in-memory ledger with:

- account creation
- balance lookup
- atomic transfers
- debit and credit ledger entries
- idempotency keys
- basic correctness tests

Money is represented as integer cents (`long`) to avoid floating point rounding.

## Run Tests

```sh
javac -d out $(find src/main/java src/test/java -name '*.java')
java -cp out com.example.ledger.LedgerServiceTest
```

## Run Interactive CLI

```sh
javac -d out $(find src/main/java -name '*.java')
java -cp out com.example.ledger.LedgerCli
```

Available commands:

```text
create-account <owner_name> <initial_balance_cents>
balance <account_id>
transfer <from_account_id> <to_account_id> <amount_cents> <idempotency_key>
entries <account_id>
help
exit
```

Example session:

```text
ledger> create-account ada 10000
account_id=<copy-this-id>
balance_cents=10000
ledger> create-account grace 0
account_id=<copy-this-id>
balance_cents=0
ledger> transfer <ada-id> <grace-id> 2500 transfer-1
transfer_id=<transfer-id>
replayed=false
from_balance_cents=7500
to_balance_cents=2500
```
