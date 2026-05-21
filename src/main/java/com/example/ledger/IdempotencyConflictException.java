package com.example.ledger;

public class IdempotencyConflictException extends LedgerException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
