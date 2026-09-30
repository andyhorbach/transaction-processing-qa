package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.common.ApiException;

/**
 * TRANSFER is deliberately absent in this version (contract section 7).
 */
public enum TransactionType {
    DEPOSIT(false),
    WITHDRAWAL(true),
    REFUND(false),
    FEE(true);

    private final boolean debit;

    TransactionType(boolean debit) {
        this.debit = debit;
    }

    public boolean isDebit() {
        return debit;
    }

    /**
     * Refund eligibility per contract decision D-7.
     */
    public boolean isRefundable() {
        return this == WITHDRAWAL || this == FEE;
    }

    public static TransactionType parse(String raw) {
        if (raw == null) {
            throw ApiException.validation("type is required");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("unsupported transaction type: " + raw);
        }
    }
}
