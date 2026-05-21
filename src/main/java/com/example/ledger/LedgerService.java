package com.example.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class LedgerService {
    private final Clock clock;
    private final Object stateLock = new Object();
    private final AccountLockManager accountLocks = new AccountLockManager();
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

    public Account createAccount(String ownerName, long initialBalanceCents) {
        if (initialBalanceCents < 0) {
            throw new IllegalArgumentException("initialBalanceCents must not be negative");
        }

        Account account = new Account(UUID.randomUUID(), ownerName, now());

        synchronized (stateLock) {
            accounts.put(account.id(), account);
            accountLocks.ensureLock(account.id());

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
        }

        return account;
    }

    public Account getAccount(UUID accountId) {
        synchronized (stateLock) {
            return requireAccount(accountId);
        }
    }

    public long getBalance(UUID accountId) {
        ReentrantLock accountLock = lockForExistingAccount(accountId);
        accountLock.lock();
        try {
            synchronized (stateLock) {
                requireAccount(accountId);
                return getBalanceUnsafe(accountId);
            }
        } finally {
            accountLock.unlock();
        }
    }

    public TransferResult transfer(
            UUID fromAccountId,
            UUID toAccountId,
            long amountCents,
            String idempotencyKey
    ) {
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must differ");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        synchronized (stateLock) {
            Transfer existing = transfersByIdempotencyKey.get(idempotencyKey);
            if (existing != null) {
                return replayOrReject(existing, fromAccountId, toAccountId, amountCents);
            }
            requireAccount(fromAccountId);
            requireAccount(toAccountId);
        }

        AccountLockManager.LockPair lockPair = accountLocks.orderedLocks(fromAccountId, toAccountId);
        lockPair.lock();
        try {
            synchronized (stateLock) {
                Transfer existing = transfersByIdempotencyKey.get(idempotencyKey);
                if (existing != null) {
                    return replayOrReject(existing, fromAccountId, toAccountId, amountCents);
                }

                requireAccount(fromAccountId);
                requireAccount(toAccountId);

                long fromBalance = getBalanceUnsafe(fromAccountId);
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
        } finally {
            lockPair.unlock();
        }
    }

    public Transfer getTransfer(UUID transferId) {
        synchronized (stateLock) {
            Transfer transfer = transfers.get(transferId);
            if (transfer == null) {
                throw new LedgerException("transfer not found: " + transferId);
            }
            return transfer;
        }
    }

    public List<LedgerEntry> listLedgerEntries(UUID accountId) {
        ReentrantLock accountLock = lockForExistingAccount(accountId);
        accountLock.lock();
        try {
            synchronized (stateLock) {
                requireAccount(accountId);
                return ledgerEntries.stream()
                        .filter(entry -> entry.accountId().equals(accountId))
                        .toList();
            }
        } finally {
            accountLock.unlock();
        }
    }

    public LedgerVerificationResult verify() {
        synchronized (stateLock) {
            List<String> violations = new ArrayList<>();

            verifyLedgerEntriesReferenceAccounts(violations);
            verifyTransfersHaveBalancedEntries(violations);

            return new LedgerVerificationResult(violations);
        }
    }

    private void verifyLedgerEntriesReferenceAccounts(List<String> violations) {
        for (LedgerEntry entry : ledgerEntries) {
            if (!accounts.containsKey(entry.accountId())) {
                violations.add("ledger entry references missing account: entry_id=" + entry.id()
                        + " account_id=" + entry.accountId());
            }
        }
    }

    private void verifyTransfersHaveBalancedEntries(List<String> violations) {
        Set<UUID> transferIdsFromEntries = ledgerEntries.stream()
                .map(LedgerEntry::transferId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (UUID transferId : transferIdsFromEntries) {
            if (!transfers.containsKey(transferId)) {
                violations.add("ledger entry references missing transfer: transfer_id=" + transferId);
            }
        }

        for (Transfer transfer : transfers.values()) {
            List<LedgerEntry> entries = ledgerEntries.stream()
                    .filter(entry -> transfer.id().equals(entry.transferId()))
                    .toList();

            long debits = entries.stream().filter(entry -> entry.type() == EntryType.DEBIT).count();
            long credits = entries.stream().filter(entry -> entry.type() == EntryType.CREDIT).count();
            long sum = entries.stream().mapToLong(LedgerEntry::signedAmountCents).sum();

            if (entries.size() != 2) {
                violations.add("transfer must have exactly two ledger entries: transfer_id=" + transfer.id()
                        + " entries=" + entries.size());
            }
            if (debits != 1 || credits != 1) {
                violations.add("transfer must have one debit and one credit: transfer_id=" + transfer.id()
                        + " debits=" + debits + " credits=" + credits);
            }
            if (sum != 0) {
                violations.add("transfer entries must balance to zero: transfer_id=" + transfer.id()
                        + " sum=" + sum);
            }
        }
    }

    private ReentrantLock lockForExistingAccount(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        synchronized (stateLock) {
            requireAccount(accountId);
        }
        return accountLocks.lockForAccount(accountId);
    }

    private Account requireAccount(UUID accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }

    private long getBalanceUnsafe(UUID accountId) {
        return ledgerEntries.stream()
                .filter(entry -> entry.accountId().equals(accountId))
                .mapToLong(LedgerEntry::signedAmountCents)
                .sum();
    }

    private TransferResult replayOrReject(Transfer transfer, UUID fromAccountId, UUID toAccountId, long amountCents) {
        if (!sameRequest(transfer, fromAccountId, toAccountId, amountCents)) {
            throw new IdempotencyConflictException("idempotency key already used with different transfer details");
        }
        return new TransferResult(transfer, true);
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
