package com.example.ledger;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public class AccountLockManager {
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void ensureLock(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        locks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
    }

    public LockPair orderedLocks(UUID firstAccountId, UUID secondAccountId) {
        Objects.requireNonNull(firstAccountId, "firstAccountId");
        Objects.requireNonNull(secondAccountId, "secondAccountId");

        int comparison = firstAccountId.compareTo(secondAccountId);
        if (comparison < 0) {
            return new LockPair(lockFor(firstAccountId), lockFor(secondAccountId));
        }
        if (comparison > 0) {
            return new LockPair(lockFor(secondAccountId), lockFor(firstAccountId));
        }
        throw new IllegalArgumentException("account IDs must differ");
    }

    public ReentrantLock lockForAccount(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return lockFor(accountId);
    }

    private ReentrantLock lockFor(UUID accountId) {
        ReentrantLock lock = locks.get(accountId);
        if (lock == null) {
            throw new AccountNotFoundException(accountId);
        }
        return lock;
    }

    public record LockPair(ReentrantLock first, ReentrantLock second) {
        public void lock() {
            first.lock();
            second.lock();
        }

        public void unlock() {
            try {
                second.unlock();
            } finally {
                first.unlock();
            }
        }
    }
}
