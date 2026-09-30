package io.github.andyhorbach.txnqa.account;

import io.github.andyhorbach.txnqa.common.Currency;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(
        UUID id,
        UUID userId,
        Currency currency,
        BigDecimal balance,
        String status,
        OffsetDateTime createdAt) {
}
