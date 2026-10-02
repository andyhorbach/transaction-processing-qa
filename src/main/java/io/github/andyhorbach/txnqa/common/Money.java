package io.github.andyhorbach.txnqa.common;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Monetary amounts travel as decimal strings (contract section 1) and are held as
 * BigDecimal with scale 2 — binary floating point never enters the money path (R-11).
 * The full input contract is decision D-10.
 */
public final class Money {

    private static final Pattern AMOUNT_FORMAT = Pattern.compile("(0|[1-9]\\d*)(\\.\\d{1,2})?");
    private static final BigDecimal MINIMUM = new BigDecimal("0.01");
    private static final BigDecimal MAXIMUM = new BigDecimal("1000000.00");

    private Money() {
    }

    /**
     * Takes the raw deserialized JSON value: anything but a JSON string is rejected,
     * so a number can never be coerced into an amount (D-10, R-22). Bounds are
     * checked here, before any value can reach the database.
     */
    public static BigDecimal parseAmount(Object raw) {
        if (!(raw instanceof String text) || !AMOUNT_FORMAT.matcher(text).matches()) {
            throw ApiException.validation(
                    "amount must be a positive decimal string with at most 2 fraction digits");
        }
        BigDecimal value = new BigDecimal(text).setScale(2);
        if (value.compareTo(MINIMUM) < 0) {
            throw ApiException.validation("amount must be at least 0.01");
        }
        if (value.compareTo(MAXIMUM) > 0) {
            throw ApiException.validation("amount must be at most 1000000.00");
        }
        return value;
    }

    public static String format(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
