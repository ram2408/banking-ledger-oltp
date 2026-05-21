package com.example.ledger;

import java.util.UUID;

public class AccountNotFoundException extends LedgerException {
    public AccountNotFoundException(UUID accountId) {
        super("account not found: " + accountId);
    }
}
