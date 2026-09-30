package io.github.andyhorbach.txnqa.common;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Monetary amounts travel as decimal strings (contract section 1) and are held as
 * BigDecimal with scale 2 — binary floating point never enters the money path (R-11).
 */
public final class Money {

    private static final Pattern AMOUNT_FORMAT = Pattern.compile("(0|[1-9]\\d*)(\\.\\d{1,2})?");
    private static final BigDecimal MINIMUM = new BigDecimal("0.01");

    private Money() {
    }

    public static BigDecimal parseAmount(String raw) {
        if (raw == null || !AMOUNT_FORMAT.matcher(raw).matches()) {
            throw ApiException.validation(
                    "amount must be a positive decimal string with at most 2 fraction digits");
        }
        BigDecimal value = new BigDecimal(raw).setScale(2);
        if (value.compareTo(MINIMUM) < 0) {
            throw ApiException.validation("amount must be at least 0.01");
        }
        return value;
    }

    public static String format(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
