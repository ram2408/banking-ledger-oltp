package com.example.ledger;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class LedgerCli {
    private final LedgerService service;

    public LedgerCli(LedgerService service) {
        this.service = service;
    }

    public static void main(String[] args) {
        new LedgerCli(new LedgerService()).run();
    }

    public void run() {
        printWelcome();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("ledger> ");
                if (!scanner.hasNextLine()) {
                    System.out.println();
                    return;
                }

                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    if (handle(line)) {
                        return;
                    }
                } catch (RuntimeException error) {
                    System.out.println("ERROR " + error.getMessage());
                }
            }
        }
    }

    private boolean handle(String line) {
        List<String> parts = Arrays.stream(line.split("\\s+")).toList();
        String command = parts.get(0).toLowerCase();

        switch (command) {
            case "help" -> printHelp();
            case "create-account" -> createAccount(parts);
            case "balance" -> printBalance(parts);
            case "transfer" -> transfer(parts);
            case "entries" -> printEntries(parts);
            case "verify" -> verify(parts);
            case "exit", "quit" -> {
                System.out.println("bye");
                return true;
            }
            default -> System.out.println("Unknown command. Type 'help' for available commands.");
        }

        return false;
    }

    private void createAccount(List<String> parts) {
        requireArity(parts, 3, "create-account <owner_name> <initial_balance_cents>");

        String ownerName = parts.get(1);
        long initialBalanceCents = parseCents(parts.get(2));

        Account account = service.createAccount(ownerName, initialBalanceCents);
        System.out.println("account_id=" + account.id());
        System.out.println("balance_cents=" + service.getBalance(account.id()));
    }

    private void printBalance(List<String> parts) {
        requireArity(parts, 2, "balance <account_id>");

        UUID accountId = parseUuid(parts.get(1));
        System.out.println("balance_cents=" + service.getBalance(accountId));
    }

    private void transfer(List<String> parts) {
        requireArity(parts, 5, "transfer <from_account_id> <to_account_id> <amount_cents> <idempotency_key>");

        UUID fromAccountId = parseUuid(parts.get(1));
        UUID toAccountId = parseUuid(parts.get(2));
        long amountCents = parseCents(parts.get(3));
        String idempotencyKey = parts.get(4);

        TransferResult result = service.transfer(fromAccountId, toAccountId, amountCents, idempotencyKey);
        System.out.println("transfer_id=" + result.transfer().id());
        System.out.println("replayed=" + result.replayed());
        System.out.println("from_balance_cents=" + service.getBalance(fromAccountId));
        System.out.println("to_balance_cents=" + service.getBalance(toAccountId));
    }

    private void printEntries(List<String> parts) {
        requireArity(parts, 2, "entries <account_id>");

        UUID accountId = parseUuid(parts.get(1));
        List<LedgerEntry> entries = service.listLedgerEntries(accountId);

        if (entries.isEmpty()) {
            System.out.println("No ledger entries.");
            return;
        }

        for (LedgerEntry entry : entries) {
            System.out.printf(
                    "%s account_id=%s transfer_id=%s type=%s amount_cents=%d signed_amount_cents=%d created_at=%s%n",
                    entry.id(),
                    entry.accountId(),
                    entry.transferId() == null ? "-" : entry.transferId(),
                    entry.type(),
                    entry.amountCents(),
                    entry.signedAmountCents(),
                    entry.createdAt()
            );
        }
    }

    private void verify(List<String> parts) {
        requireArity(parts, 1, "verify");

        LedgerVerificationResult result = service.verify();
        if (result.valid()) {
            System.out.println("ledger_valid=true");
            return;
        }

        System.out.println("ledger_valid=false");
        for (String violation : result.violations()) {
            System.out.println("violation=" + violation);
        }
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid UUID: " + value);
        }
    }

    private long parseCents(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid cents amount: " + value);
        }
    }

    private void requireArity(List<String> parts, int expected, String usage) {
        if (parts.size() != expected) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private void printWelcome() {
        System.out.println("Banking Ledger OLTP");
        System.out.println("Type 'help' for commands. Type 'exit' to quit.");
    }

    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  create-account <owner_name> <initial_balance_cents>");
        System.out.println("  balance <account_id>");
        System.out.println("  transfer <from_account_id> <to_account_id> <amount_cents> <idempotency_key>");
        System.out.println("  entries <account_id>");
        System.out.println("  verify");
        System.out.println("  help");
        System.out.println("  exit");
    }
}
