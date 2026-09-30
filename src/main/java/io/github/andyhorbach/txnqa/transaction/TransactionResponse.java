package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.common.Money;

import java.time.format.DateTimeFormatter;

public record TransactionResponse(
        String id,
        String accountId,
        String type,
        String amount,
        String currency,
        String status,
        String originalTransactionId,
        String createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.id().toString(),
                transaction.accountId().toString(),
                transaction.type().name(),
                Money.format(transaction.amount()),
                transaction.currency().name(),
                transaction.status().name(),
                transaction.originalTransactionId() == null
                        ? null
                        : transaction.originalTransactionId().toString(),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(transaction.createdAt()));
    }
}
