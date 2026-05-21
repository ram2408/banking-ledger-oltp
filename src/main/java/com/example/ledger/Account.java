package com.example.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Account(UUID id, String ownerName, Instant createdAt) {
    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(createdAt, "createdAt");

        if (ownerName.isBlank()) {
            throw new IllegalArgumentException("ownerName must not be blank");
        }
    }
}
