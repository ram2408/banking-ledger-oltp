package com.example.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class LedgerService {
    private final Clock clock;
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final Map<String, Transfer> transfersByIdempotencyKey = new HashMap<>();
    private final List<LedgerEntry> ledgerEntries = new ArrayList<>();

    public LedgerService() {
        this(Clock.systemUTC());
    }

    public LedgerService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Account createAccount(String ownerName, long initialBalanceCents) {
        if (initialBalanceCents < 0) {
            throw new IllegalArgumentException("initialBalanceCents must not be negative");
        }

        Account account = new Account(UUID.randomUUID(), ownerName, now());
        accounts.put(account.id(), account);

        if (initialBalanceCents > 0) {
            ledgerEntries.add(new LedgerEntry(
                    UUID.randomUUID(),
                    account.id(),
                    null,
                    EntryType.CREDIT,
                    initialBalanceCents,
                    now()
            ));
        }

        return account;
    }

    public synchronized Account getAccount(UUID accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }

    public synchronized long getBalance(UUID accountId) {
        requireAccount(accountId);
        return ledgerEntries.stream()
                .filter(entry -> entry.accountId().equals(accountId))
                .mapToLong(LedgerEntry::signedAmountCents)
                .sum();
    }

    public synchronized TransferResult transfer(
            UUID fromAccountId,
            UUID toAccountId,
            long amountCents,
            String idempotencyKey
    ) {
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        Transfer existing = transfersByIdempotencyKey.get(idempotencyKey);
        if (existing != null) {
            if (!sameRequest(existing, fromAccountId, toAccountId, amountCents)) {
                throw new IdempotencyConflictException("idempotency key already used with different transfer details");
            }
            return new TransferResult(existing, true);
        }

        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must differ");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        requireAccount(fromAccountId);
        requireAccount(toAccountId);

        long fromBalance = getBalance(fromAccountId);
        if (fromBalance < amountCents) {
            throw new InsufficientFundsException("insufficient funds");
        }

        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                fromAccountId,
                toAccountId,
                amountCents,
                idempotencyKey,
                now()
        );

        LedgerEntry debit = new LedgerEntry(
                UUID.randomUUID(),
                fromAccountId,
                transfer.id(),
                EntryType.DEBIT,
                amountCents,
                now()
        );
        LedgerEntry credit = new LedgerEntry(
                UUID.randomUUID(),
                toAccountId,
                transfer.id(),
                EntryType.CREDIT,
                amountCents,
                now()
        );

        assertBalanced(debit, credit);

        transfers.put(transfer.id(), transfer);
        transfersByIdempotencyKey.put(idempotencyKey, transfer);
        ledgerEntries.add(debit);
        ledgerEntries.add(credit);

        return new TransferResult(transfer, false);
    }

    public synchronized Transfer getTransfer(UUID transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new LedgerException("transfer not found: " + transferId);
        }
        return transfer;
    }

    public synchronized List<LedgerEntry> listLedgerEntries(UUID accountId) {
        requireAccount(accountId);
        return ledgerEntries.stream()
                .filter(entry -> entry.accountId().equals(accountId))
                .toList();
    }

    private Account requireAccount(UUID accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }

    private boolean sameRequest(Transfer transfer, UUID fromAccountId, UUID toAccountId, long amountCents) {
        return transfer.fromAccountId().equals(fromAccountId)
                && transfer.toAccountId().equals(toAccountId)
                && transfer.amountCents() == amountCents;
    }

    private void assertBalanced(LedgerEntry debit, LedgerEntry credit) {
        long sum = debit.signedAmountCents() + credit.signedAmountCents();
        if (sum != 0) {
            throw new IllegalStateException("transfer ledger entries do not balance");
        }
    }

    private Instant now() {
        return clock.instant();
    }
}
