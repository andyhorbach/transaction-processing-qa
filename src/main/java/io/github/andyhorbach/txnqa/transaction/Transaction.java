package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.common.Currency;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Transaction(
        UUID id,
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        Currency currency,
        TransactionStatus status,
        UUID originalTransactionId,
        OffsetDateTime createdAt) {
}
