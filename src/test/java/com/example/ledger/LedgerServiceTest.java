package com.example.ledger;

import java.util.List;
import java.util.Objects;

public class LedgerServiceTest {
    public static void main(String[] args) {
        run("creates accounts with opening balance", LedgerServiceTest::createsAccountsWithOpeningBalance);
        run("transfers money atomically", LedgerServiceTest::transfersMoneyAtomically);
        run("rejects overdraft", LedgerServiceTest::rejectsOverdraft);
        run("replays idempotent transfer", LedgerServiceTest::replaysIdempotentTransfer);
        run("rejects idempotency conflict", LedgerServiceTest::rejectsIdempotencyConflict);
        run("lists ledger entries by account", LedgerServiceTest::listsLedgerEntriesByAccount);
        run("verifies valid ledger", LedgerServiceTest::verifiesValidLedger);
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
}
