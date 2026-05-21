package com.example.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Transfer(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        String idempotencyKey,
        Instant createdAt
) {
    public Transfer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");

        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must differ");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
