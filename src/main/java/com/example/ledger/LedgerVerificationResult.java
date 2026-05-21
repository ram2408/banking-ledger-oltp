package com.example.ledger;

import java.util.List;

public record LedgerVerificationResult(List<String> violations) {
    public LedgerVerificationResult {
        violations = List.copyOf(violations);
    }

    public boolean valid() {
        return violations.isEmpty();
    }
}
