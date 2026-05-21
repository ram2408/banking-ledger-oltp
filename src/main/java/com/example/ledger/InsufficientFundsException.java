package com.example.ledger;

public class InsufficientFundsException extends LedgerException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
