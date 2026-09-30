package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.common.ApiException;

/**
 * Lifecycle per the risk analysis: PENDING → PROCESSING → COMPLETED | FAILED.
 * Everything else — including any move out of a terminal state — is invalid (R-04, R-06).
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public static boolean isValidTransition(TransactionStatus from, TransactionStatus to) {
        return (from == PENDING && to == PROCESSING)
                || (from == PROCESSING && to == COMPLETED)
                || (from == PROCESSING && to == FAILED);
    }

    public static TransactionStatus parse(String raw) {
        if (raw == null) {
            throw ApiException.validation("to is required");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("unknown status: " + raw);
        }
    }
}
