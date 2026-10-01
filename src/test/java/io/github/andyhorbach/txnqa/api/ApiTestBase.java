package io.github.andyhorbach.txnqa.api;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Shared infrastructure for the API test suite. The application runs in-JVM on a
 * random port against a real PostgreSQL (embedded-pg profile), so the suite needs
 * no Docker and asserts against the same database the application writes to.
 *
 * Test isolation is by fixture design: every test creates its own account(s), so
 * no state leaks between tests and the single Spring context is reused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded-pg")
public abstract class ApiTestBase {

    protected static final String ALICE = "qa-token-alice";
    protected static final String BOB = "qa-token-bob";
    protected static final UUID ALICE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JdbcClient jdbc;

    protected LedgerOracle oracle() {
        return new LedgerOracle(jdbc);
    }

    /** Authenticated spec without a content type — for GET requests (no body). */
    protected RequestSpecification auth(String token) {
        return RestAssured.given()
                .baseUri("http://localhost")
                .port(port)
                .header("Authorization", "Bearer " + token);
    }

    /** Authenticated spec for requests that carry a JSON body. */
    protected RequestSpecification authJson(String token) {
        return auth(token).contentType("application/json");
    }

    protected RequestSpecification noAuth() {
        return RestAssured.given().baseUri("http://localhost").port(port);
    }

    protected String createAccount(String token, String currency) {
        return authJson(token)
                .body("{\"currency\":\"%s\"}".formatted(currency))
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");
    }

    protected String txBody(String type, String amount, String currency) {
        return "{\"type\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}".formatted(type, amount, currency);
    }

    protected String refundBody(String amount, String currency, String originalTransactionId) {
        return "{\"type\":\"REFUND\",\"amount\":\"%s\",\"currency\":\"%s\",\"original_transaction_id\":\"%s\"}"
                .formatted(amount, currency, originalTransactionId);
    }

    protected Response postTransaction(String token, String accountId, String idempotencyKey, String jsonBody) {
        RequestSpecification spec = authJson(token).body(jsonBody);
        if (idempotencyKey != null) {
            spec.header("Idempotency-Key", idempotencyKey);
        }
        return spec.post("/accounts/{accountId}/transactions", accountId);
    }

    protected Response transition(String token, String transactionId, String to) {
        return authJson(token)
                .body("{\"to\":\"%s\"}".formatted(to))
                .post("/transactions/{transactionId}/transitions", transactionId);
    }

    /** Creates a transaction and drives it PENDING -> PROCESSING -> COMPLETED. */
    protected String createCompleted(String accountId, String type, String amount, String currency) {
        String id = postTransaction(ALICE, accountId, newKey(), txBody(type, amount, currency))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, id, "PROCESSING").then().statusCode(200);
        transition(ALICE, id, "COMPLETED").then().statusCode(200);
        return id;
    }

    /** A fresh AUD account of alice's, funded via a completed deposit. */
    protected String fundedAccount(String amount) {
        String accountId = createAccount(ALICE, "AUD");
        createCompleted(accountId, "DEPOSIT", amount, "AUD");
        return accountId;
    }

    protected String apiBalance(String token, String accountId) {
        return auth(token).get("/accounts/{accountId}", accountId)
                .then().statusCode(200).extract().path("balance");
    }

    protected String dbTransactionStatus(String transactionId) {
        return jdbc.sql("SELECT status FROM account_transaction WHERE id = :id")
                .param("id", UUID.fromString(transactionId))
                .query(String.class).single();
    }

    protected long transactionCount(String accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM account_transaction WHERE account_id = :id")
                .param("id", UUID.fromString(accountId))
                .query(Long.class).single();
    }

    protected String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Fires n calls as actual parallel requests, released simultaneously by a
     * barrier, and returns the responses in completion-independent order.
     */
    protected List<Response> inParallel(int n, Supplier<Response> call) {
        CyclicBarrier barrier = new CyclicBarrier(n);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit((Callable<Response>) () -> {
                    barrier.await();
                    return call.get();
                }));
            }
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                try {
                    responses.add(future.get());
                } catch (Exception e) {
                    throw new IllegalStateException("Parallel call failed", e);
                }
            }
            return responses;
        }
    }
}
