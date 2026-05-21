package com.example.ledger;

public record TransferResult(Transfer transfer, boolean replayed) {
}
