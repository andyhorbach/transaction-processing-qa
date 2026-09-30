package io.github.andyhorbach.txnqa.common;

/**
 * Supported currencies (contract decision D-2). All use scale 2 (D-3).
 */
public enum Currency {
    AUD, USD, EUR;

    public static Currency parse(String raw) {
        if (raw == null) {
            throw ApiException.validation("currency is required");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("unsupported currency: " + raw);
        }
    }
}
