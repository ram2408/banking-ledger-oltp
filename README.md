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
