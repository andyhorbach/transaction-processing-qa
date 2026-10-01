package io.github.andyhorbach.txnqa.api;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end journeys: realistic multi-step lifecycles asserting cumulative
 * state, which the per-risk API suites structurally do not cover.
 *
 * These three tests are COMPLEMENTARY coverage over the risk-mapped suites —
 * they sweep across many risks at journey level but do not by themselves
 * prove any risk; the focused per-risk tests remain the primary evidence.
 */
class EndToEndJourneyTest extends ApiTestBase {

    @Test
    @DisplayName("E2E: customer lifecycle — fund, spend, fail, refund (complements R-01..R-03, R-13, R-18, R-19)")
    void customerLifecycleJourney() {
        String account = createAccount(ALICE, "AUD");

        String deposit = createCompleted(account, "DEPOSIT", "200.00", "AUD");
        String withdrawal = createCompleted(account, "WITHDRAWAL", "80.00", "AUD");
        String fee = createCompleted(account, "FEE", "5.00", "AUD");

        // One failed attempt: created, processed, then failed — must leave no trace on the balance.
        String failedAttempt = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "50.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, failedAttempt, "PROCESSING").then().statusCode(200);
        transition(ALICE, failedAttempt, "FAILED").then().statusCode(200);

        String refund = postTransaction(ALICE, account, newKey(), refundBody("30.00", "AUD", withdrawal))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, refund, "PROCESSING").then().statusCode(200);
        transition(ALICE, refund, "COMPLETED").then().statusCode(200);

        // Hand-computed per contract section 4: +200.00 -80.00 -5.00 (FAILED: 0) +30.00 = 145.00
        assertThat(apiBalance(ALICE, account)).isEqualTo("145.00");

        // Complete history: exact count and newest-first order.
        List<String> historyIds = auth(ALICE).get("/accounts/{id}/transactions", account)
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(historyIds).containsExactly(refund, failedAttempt, fee, withdrawal, deposit);

        // Independent oracle and API/DB agreement.
        oracle().assertLedgerConsistent(account);
        assertThat(oracle().persistedBalance(UUID.fromString(account)))
                .isEqualByComparingTo(new BigDecimal("145.00"));
    }

    @Test
    @DisplayName("E2E: unreliable client retries every step (complements R-04, R-07..R-09, contract 4.1/5)")
    void unreliableClientJourney() {
        String account = createAccount(ALICE, "AUD");

        // Deposit 100.00 — the creation is retried with the SAME key: must replay, not duplicate.
        String depositKey = newKey();
        String depositBody = txBody("DEPOSIT", "100.00", "AUD");
        String deposit = postTransaction(ALICE, account, depositKey, depositBody)
                .then().statusCode(201).extract().path("id");
        Response depositRetry = postTransaction(ALICE, account, depositKey, depositBody);
        depositRetry.then().statusCode(201).body("id", equalTo(deposit));
        assertThat(depositRetry.getHeader("Idempotency-Replay")).isEqualTo("true");

        // Transitions are retried too: the state matrix is the retry guard.
        transition(ALICE, deposit, "PROCESSING").then().statusCode(200);
        transition(ALICE, deposit, "PROCESSING").then().statusCode(409);
        transition(ALICE, deposit, "COMPLETED").then().statusCode(200);
        transition(ALICE, deposit, "COMPLETED").then().statusCode(409);
        assertThat(apiBalance(ALICE, account)).as("retried transitions apply no double effect").isEqualTo("100.00");

        // A plainly impossible request is rejected and creates nothing.
        postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "150.00", "AUD"))
                .then().statusCode(422);

        // Two withdrawals pass the advisory check against 100.00; only one can complete now.
        String w1 = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "60.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        String w2 = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "60.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, w1, "PROCESSING").then().statusCode(200);
        transition(ALICE, w2, "PROCESSING").then().statusCode(200);
        transition(ALICE, w1, "COMPLETED").then().statusCode(200);

        // Completion rejected for insufficient funds; the client retries — same result, no corruption.
        transition(ALICE, w2, "COMPLETED").then().statusCode(422)
                .body("error.code", equalTo("INSUFFICIENT_FUNDS"));
        transition(ALICE, w2, "COMPLETED").then().statusCode(422);
        assertThat(dbTransactionStatus(w2)).isEqualTo("PROCESSING");

        // Fresh funds arrive (that creation also retried/replayed), then the completion retry succeeds.
        String topUpKey = newKey();
        String topUpBody = txBody("DEPOSIT", "20.00", "AUD");
        String topUp = postTransaction(ALICE, account, topUpKey, topUpBody)
                .then().statusCode(201).extract().path("id");
        postTransaction(ALICE, account, topUpKey, topUpBody)
                .then().statusCode(201).body("id", equalTo(topUp));
        transition(ALICE, topUp, "PROCESSING").then().statusCode(200);
        transition(ALICE, topUp, "COMPLETED").then().statusCode(200);
        transition(ALICE, w2, "COMPLETED").then().statusCode(200);

        // Final state verified independently: hand-computed balance, exact row count, oracle.
        // +100.00 -60.00 -60.00 +20.00 = 0.00; 4 logical operations -> exactly 4 transactions.
        assertThat(apiBalance(ALICE, account)).isEqualTo("0.00");
        assertThat(transactionCount(account))
                .as("every logical operation has exactly one transaction despite all retries")
                .isEqualTo(4);
        oracle().assertLedgerConsistent(account);
        assertThat(oracle().persistedBalance(UUID.fromString(account)))
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("E2E: concurrent tenants stay isolated throughout (complements R-16, R-17, R-19)")
    void concurrentTenantsJourney() throws Exception {
        String aliceAccount = createAccount(ALICE, "AUD");
        String bobAccount = createAccount(BOB, "AUD");
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> alice = pool.submit(() -> {
                barrier.await();
                return runTenantJourney(ALICE, aliceAccount, bobAccount,
                        "100.00", "30.00", "10.00", "5.00");
            });
            Future<?> bob = pool.submit(() -> {
                barrier.await();
                return runTenantJourney(BOB, bobAccount, aliceAccount,
                        "200.00", "50.00", "20.00", "15.00");
            });
            alice.get();
            bob.get();
        }

        // Per contract section 4, by hand: alice +100-30-10+5 = 65.00; bob +200-50-20+15 = 145.00.
        assertThat(apiBalance(ALICE, aliceAccount)).isEqualTo("65.00");
        assertThat(apiBalance(BOB, bobAccount)).isEqualTo("145.00");
        assertThat(transactionCount(aliceAccount)).as("only alice's 4 operations on her account").isEqualTo(4);
        assertThat(transactionCount(bobAccount)).as("only bob's 4 operations on his account").isEqualTo(4);
        oracle().assertLedgerConsistent(aliceAccount);
        oracle().assertLedgerConsistent(bobAccount);
    }

    /**
     * One tenant's journey: fund, spend (withdrawal + fee), partially refund the
     * withdrawal — probing the OTHER tenant's account mid-journey at every stage.
     */
    private Void runTenantJourney(String token, String ownAccount, String foreignAccount,
                                  String depositAmount, String withdrawalAmount,
                                  String feeAmount, String refundAmount) {
        completedTransaction(token, ownAccount, txBody("DEPOSIT", depositAmount, "AUD"));
        assertForeignAccountInvisible(token, foreignAccount);

        String withdrawal = completedTransaction(token, ownAccount, txBody("WITHDRAWAL", withdrawalAmount, "AUD"));
        assertForeignAccountInvisible(token, foreignAccount);

        completedTransaction(token, ownAccount, txBody("FEE", feeAmount, "AUD"));
        completedTransaction(token, ownAccount, refundBody(refundAmount, "AUD", withdrawal));
        assertForeignAccountInvisible(token, foreignAccount);
        return null;
    }

    private String completedTransaction(String token, String accountId, String jsonBody) {
        String id = postTransaction(token, accountId, newKey(), jsonBody)
                .then().statusCode(201).extract().path("id");
        transition(token, id, "PROCESSING").then().statusCode(200);
        transition(token, id, "COMPLETED").then().statusCode(200);
        return id;
    }

    /** Mid-journey isolation probe: reads and writes on the other tenant's account must 404 (D-1). */
    private void assertForeignAccountInvisible(String token, String foreignAccount) {
        auth(token).get("/accounts/{id}", foreignAccount)
                .then().statusCode(404).body("error.code", equalTo("NOT_FOUND"));
        postTransaction(token, foreignAccount, newKey(), txBody("DEPOSIT", "1.00", "AUD"))
                .then().statusCode(404).body("error.code", equalTo("NOT_FOUND"));
    }
}
