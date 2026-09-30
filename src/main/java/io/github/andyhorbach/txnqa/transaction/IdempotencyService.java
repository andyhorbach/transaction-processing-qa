package io.github.andyhorbach.txnqa.transaction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Implements contract section 5. The claim record is written in its own
 * transaction (REQUIRES_NEW) so concurrent duplicates observe it immediately,
 * before the business transaction commits (D-6, R-09).
 *
 * The stored record references the created transaction id instead of a
 * serialized response body: replays re-read the row and serialize normally,
 * so there is exactly one source of truth for what the response looks like.
 */
@Service
public class IdempotencyService {

    public enum Outcome {NEW, REPLAY, IN_FLIGHT, PAYLOAD_MISMATCH}

    public record BeginResult(Outcome outcome, UUID transactionId) {
    }

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(UUID userId, String key, String requestHash) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_record (user_id, idempotency_key, request_hash, in_flight)
                        VALUES (:userId, :key, :hash, true)
                        ON CONFLICT DO NOTHING
                        """)
                .param("userId", userId)
                .param("key", key)
                .param("hash", requestHash)
                .update();
        if (inserted == 1) {
            return new BeginResult(Outcome.NEW, null);
        }
        return jdbc.sql("""
                        SELECT request_hash, in_flight, transaction_id
                        FROM idempotency_record
                        WHERE user_id = :userId AND idempotency_key = :key
                        """)
                .param("userId", userId)
                .param("key", key)
                .query((rs, rowNum) -> {
                    if (!requestHash.equals(rs.getString("request_hash"))) {
                        return new BeginResult(Outcome.PAYLOAD_MISMATCH, null);
                    }
                    if (rs.getBoolean("in_flight")) {
                        return new BeginResult(Outcome.IN_FLIGHT, null);
                    }
                    return new BeginResult(Outcome.REPLAY, rs.getObject("transaction_id", UUID.class));
                })
                .single();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID userId, String key, UUID transactionId) {
        jdbc.sql("""
                        UPDATE idempotency_record
                        SET in_flight = false, transaction_id = :transactionId
                        WHERE user_id = :userId AND idempotency_key = :key
                        """)
                .param("userId", userId)
                .param("key", key)
                .param("transactionId", transactionId)
                .update();
    }

    /**
     * A rejected request releases its key so the client may retry with the
     * same key after correcting the cause; only successful creation binds a key.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID userId, String key) {
        jdbc.sql("DELETE FROM idempotency_record WHERE user_id = :userId AND idempotency_key = :key")
                .param("userId", userId)
                .param("key", key)
                .update();
    }

    public static String requestHash(UUID accountId, TransactionType type, String normalizedAmount,
                                     String currency, UUID originalTransactionId) {
        String canonical = accountId + "\n" + type + "\n" + normalizedAmount + "\n" + currency + "\n"
                + (originalTransactionId == null ? "" : originalTransactionId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
