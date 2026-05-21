package com.example.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LedgerEntry(
        UUID id,
        UUID accountId,
        UUID transferId,
        EntryType type,
        long amountCents,
        Instant createdAt
) {
    public LedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");

        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
    }

    public long signedAmountCents() {
        return switch (type) {
            case CREDIT -> amountCents;
            case DEBIT -> -amountCents;
        };
    }
}
