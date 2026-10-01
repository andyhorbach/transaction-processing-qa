package io.github.andyhorbach.txnqa.api;

import io.github.andyhorbach.txnqa.transaction.IdempotencyService;
import io.github.andyhorbach.txnqa.transaction.TransactionType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Idempotency — risks R-07…R-09 and the key-lifecycle rules of api-contract.md
 * section 5. Covers the complete decision table:
 *
 *   key state   | same payload                       | different payload
 *   ------------+------------------------------------+---------------------------
 *   unbound     | 201 creates (baseline of each test)| 201 creates
 *   in flight   | 409 DUPLICATE_REQUEST_IN_PROGRESS  | 409 IDEMPOTENCY_KEY_REUSE
 *   bound       | 201 replay + Idempotency-Replay    | 409 IDEMPOTENCY_KEY_REUSE
 *
 * plus: a rejected creation does not bind the key.
 *
 * The in-flight rows are seeded directly in the database because the real
 * in-flight window is milliseconds wide; the production hash helper is used
 * only for that seeding. Hash *sensitivity* is still proven end-to-end through
 * the pure-API bound-key tests, so a degenerate hash cannot pass both sides.
 */
class IdempotencyTest extends ApiTestBase {

    @Test
    @DisplayName("R-07: bound key + same payload replays the original 201 and creates nothing")
    void boundKeySamePayloadReplays() {
        String account = createAccount(ALICE, "AUD");
        String key = newKey();
        String body = txBody("DEPOSIT", "10.00", "AUD");

        Response first = postTransaction(ALICE, account, key, body);
        first.then().statusCode(201);
        assertThat(first.getHeader("Idempotency-Replay")).as("fresh creation is not a replay").isNull();
        String id = first.path("id");

        Response second = postTransaction(ALICE, account, key, body);
        second.then().statusCode(201).body("id", equalTo(id));
        assertThat(second.getHeader("Idempotency-Replay")).isEqualTo("true");
        assertThat(transactionCount(account)).as("replay must not create a second transaction").isEqualTo(1);
    }

    @Test
    @DisplayName("R-08: bound key + different payload is rejected, neither payload executed")
    void boundKeyDifferentPayloadRejected() {
        String account = createAccount(ALICE, "AUD");
        String key = newKey();
        postTransaction(ALICE, account, key, txBody("DEPOSIT", "10.00", "AUD")).then().statusCode(201);

        postTransaction(ALICE, account, key, txBody("DEPOSIT", "999.00", "AUD"))
                .then().statusCode(409)
                .body("error.code", equalTo("IDEMPOTENCY_KEY_REUSE"));

        assertThat(transactionCount(account)).isEqualTo(1);
    }

    @Test
    @DisplayName("Contract 5: a rejected creation does not bind the key; same key retries after the precondition is met")
    void rejectedCreationReleasesKey() {
        String account = createAccount(ALICE, "AUD");
        String key = newKey();
        String body = txBody("WITHDRAWAL", "50.00", "AUD");

        postTransaction(ALICE, account, key, body)
                .then().statusCode(422)
                .body("error.code", equalTo("INSUFFICIENT_FUNDS"));

        createCompleted(account, "DEPOSIT", "50.00", "AUD");

        Response retry = postTransaction(ALICE, account, key, body);
        retry.then().statusCode(201);
        assertThat(retry.getHeader("Idempotency-Replay")).as("a fresh creation, not a replay").isNull();
    }

    @Test
    @DisplayName("D-6: in-flight key + same payload -> 409 DUPLICATE_REQUEST_IN_PROGRESS")
    void inFlightSamePayload() {
        String account = createAccount(ALICE, "AUD");
        String key = newKey();
        String hash = IdempotencyService.requestHash(
                UUID.fromString(account), TransactionType.DEPOSIT, "5.00", "AUD", null);
        seedInFlight(key, hash);
        try {
            postTransaction(ALICE, account, key, txBody("DEPOSIT", "5.00", "AUD"))
                    .then().statusCode(409)
                    .body("error.code", equalTo("DUPLICATE_REQUEST_IN_PROGRESS"));
            assertThat(transactionCount(account)).isZero();
        } finally {
            deleteIdempotencyRecord(key);
        }
    }

    @Test
    @DisplayName("Contract 5: in-flight key + different payload -> 409 IDEMPOTENCY_KEY_REUSE (mismatch wins)")
    void inFlightDifferentPayload() {
        String account = createAccount(ALICE, "AUD");
        String key = newKey();
        String hash = IdempotencyService.requestHash(
                UUID.fromString(account), TransactionType.DEPOSIT, "5.00", "AUD", null);
        seedInFlight(key, hash);
        try {
            postTransaction(ALICE, account, key, txBody("DEPOSIT", "7.00", "AUD"))
                    .then().statusCode(409)
                    .body("error.code", equalTo("IDEMPOTENCY_KEY_REUSE"));
            assertThat(transactionCount(account)).isZero();
        } finally {
            deleteIdempotencyRecord(key);
        }
    }

    @Test
    @DisplayName("R-09: parallel duplicates with one key produce exactly one transaction")
    void parallelDuplicatesCreateExactlyOne() {
        String account = createAccount(ALICE, "AUD");
        String key = newKey();
        String body = txBody("DEPOSIT", "5.00", "AUD");

        List<Response> responses = inParallel(5, () -> postTransaction(ALICE, account, key, body));

        assertThat(transactionCount(account)).as("exactly one transaction row").isEqualTo(1);
        long freshCreations = responses.stream()
                .filter(r -> r.statusCode() == 201 && r.getHeader("Idempotency-Replay") == null)
                .count();
        assertThat(freshCreations).as("exactly one request actually creates").isEqualTo(1);
        String winnerId = responses.stream()
                .filter(r -> r.statusCode() == 201 && r.getHeader("Idempotency-Replay") == null)
                .findFirst().orElseThrow().path("id");
        for (Response r : responses) {
            if (r.statusCode() == 201 && "true".equals(r.getHeader("Idempotency-Replay"))) {
                assertThat(r.<String>path("id")).as("replays return the winner's transaction").isEqualTo(winnerId);
            } else if (r.statusCode() == 409) {
                assertThat(r.<String>path("error.code")).isEqualTo("DUPLICATE_REQUEST_IN_PROGRESS");
            }
        }
    }

    private void seedInFlight(String key, String requestHash) {
        jdbc.sql("""
                        INSERT INTO idempotency_record (user_id, idempotency_key, request_hash, in_flight)
                        VALUES (:userId, :key, :hash, true)
                        """)
                .param("userId", ALICE_ID)
                .param("key", key)
                .param("hash", requestHash)
                .update();
    }

    private void deleteIdempotencyRecord(String key) {
        jdbc.sql("DELETE FROM idempotency_record WHERE user_id = :userId AND idempotency_key = :key")
                .param("userId", ALICE_ID)
                .param("key", key)
                .update();
    }
}
