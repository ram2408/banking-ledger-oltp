package com.example.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class LedgerServiceTest {
    public static void main(String[] args) {
        run("creates accounts with opening balance", LedgerServiceTest::createsAccountsWithOpeningBalance);
        run("transfers money atomically", LedgerServiceTest::transfersMoneyAtomically);
        run("rejects overdraft", LedgerServiceTest::rejectsOverdraft);
        run("replays idempotent transfer", LedgerServiceTest::replaysIdempotentTransfer);
        run("rejects idempotency conflict", LedgerServiceTest::rejectsIdempotencyConflict);
        run("lists ledger entries by account", LedgerServiceTest::listsLedgerEntriesByAccount);
        run("verifies valid ledger", LedgerServiceTest::verifiesValidLedger);
        run("concurrent transfers preserve total balance", LedgerServiceTest::concurrentTransfersPreserveTotalBalance);
        run("concurrent bidirectional transfers avoid deadlock", LedgerServiceTest::concurrentBidirectionalTransfersAvoidDeadlock);
        run("concurrent overdrafts only commit funded transfers", LedgerServiceTest::concurrentOverdraftsOnlyCommitFundedTransfers);
        System.out.println("All tests passed");
    }

    private static void createsAccountsWithOpeningBalance() {
        LedgerService service = new LedgerService();

        Account account = service.createAccount("Ada", 10_00);

        assertEquals(10_00L, service.getBalance(account.id()));
    }

    private static void transfersMoneyAtomically() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 10_00);
        Account grace = service.createAccount("Grace", 0);

        TransferResult result = service.transfer(ada.id(), grace.id(), 3_50, "transfer-1");

        assertFalse(result.replayed());
        assertEquals(6_50L, service.getBalance(ada.id()));
        assertEquals(3_50L, service.getBalance(grace.id()));
    }

    private static void rejectsOverdraft() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 1_00);
        Account grace = service.createAccount("Grace", 0);

        assertThrows(InsufficientFundsException.class,
                () -> service.transfer(ada.id(), grace.id(), 2_00, "transfer-1"));

        assertEquals(1_00L, service.getBalance(ada.id()));
        assertEquals(0L, service.getBalance(grace.id()));
    }

    private static void replaysIdempotentTransfer() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 10_00);
        Account grace = service.createAccount("Grace", 0);

        TransferResult first = service.transfer(ada.id(), grace.id(), 3_00, "same-request");
        TransferResult second = service.transfer(ada.id(), grace.id(), 3_00, "same-request");

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.transfer().id(), second.transfer().id());
        assertEquals(7_00L, service.getBalance(ada.id()));
        assertEquals(3_00L, service.getBalance(grace.id()));
    }

    private static void rejectsIdempotencyConflict() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 10_00);
        Account grace = service.createAccount("Grace", 0);

        service.transfer(ada.id(), grace.id(), 3_00, "same-key");

        assertThrows(IdempotencyConflictException.class,
                () -> service.transfer(ada.id(), grace.id(), 4_00, "same-key"));
    }

    private static void listsLedgerEntriesByAccount() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 10_00);
        Account grace = service.createAccount("Grace", 0);

        service.transfer(ada.id(), grace.id(), 3_00, "transfer-1");

        List<LedgerEntry> adaEntries = service.listLedgerEntries(ada.id());
        List<LedgerEntry> graceEntries = service.listLedgerEntries(grace.id());

        assertEquals(2, adaEntries.size());
        assertEquals(1, graceEntries.size());
        assertEquals(EntryType.DEBIT, adaEntries.get(1).type());
        assertEquals(EntryType.CREDIT, graceEntries.get(0).type());
    }

    private static void verifiesValidLedger() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 10_00);
        Account grace = service.createAccount("Grace", 0);

        service.transfer(ada.id(), grace.id(), 3_00, "transfer-1");

        LedgerVerificationResult result = service.verify();

        assertTrue(result.valid());
        assertEquals(List.of(), result.violations());
    }

    private static void concurrentTransfersPreserveTotalBalance() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 100_00);
        Account grace = service.createAccount("Grace", 0);

        runConcurrently(100, index ->
                service.transfer(ada.id(), grace.id(), 1_00, "concurrent-transfer-" + index));

        assertEquals(0L, service.getBalance(ada.id()));
        assertEquals(100_00L, service.getBalance(grace.id()));
        assertTrue(service.verify().valid());
    }

    private static void concurrentBidirectionalTransfersAvoidDeadlock() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 100_00);
        Account grace = service.createAccount("Grace", 100_00);

        runConcurrently(100, index -> {
            if (index % 2 == 0) {
                service.transfer(ada.id(), grace.id(), 1_00, "ada-to-grace-" + index);
            } else {
                service.transfer(grace.id(), ada.id(), 1_00, "grace-to-ada-" + index);
            }
        });

        assertEquals(100_00L, service.getBalance(ada.id()));
        assertEquals(100_00L, service.getBalance(grace.id()));
        assertTrue(service.verify().valid());
    }

    private static void concurrentOverdraftsOnlyCommitFundedTransfers() {
        LedgerService service = new LedgerService();
        Account ada = service.createAccount("Ada", 10_00);
        Account grace = service.createAccount("Grace", 0);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(50, index -> {
            try {
                service.transfer(ada.id(), grace.id(), 1_00, "overdraft-race-" + index);
                committed.incrementAndGet();
            } catch (InsufficientFundsException error) {
                rejected.incrementAndGet();
            }
        });

        assertEquals(10, committed.get());
        assertEquals(40, rejected.get());
        assertEquals(0L, service.getBalance(ada.id()));
        assertEquals(10_00L, service.getBalance(grace.id()));
        assertTrue(service.verify().valid());
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            System.err.println("FAIL " + name);
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }

    private static <T extends Throwable> void assertThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + error.getClass().getSimpleName(), error);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void runConcurrently(int threadCount, IndexedTask task) {
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int index = 0; index < threadCount; index++) {
            int taskIndex = index;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run(taskIndex);
                } catch (Throwable error) {
                    failures.add(error);
                }
            }, "ledger-test-" + index);
            threads.add(thread);
            thread.start();
        }

        try {
            ready.await();
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running concurrent test", error);
        }

        if (!failures.isEmpty()) {
            throw new AssertionError("concurrent task failed", failures.get(0));
        }
    }

    private interface IndexedTask {
        void run(int index) throws Exception;
    }
}
