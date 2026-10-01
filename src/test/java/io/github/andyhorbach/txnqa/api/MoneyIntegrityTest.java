package io.github.andyhorbach.txnqa.api;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Money integrity — risks R-01…R-05 (docs/risk-analysis.md section 2.1).
 * Invariant: balance == opening balance + completed transactions, always,
 * under any concurrency. Verified against the LedgerOracle, not only the API.
 */
class MoneyIntegrityTest extends ApiTestBase {

    @Test
    @DisplayName("R-01: withdrawal exceeding the balance is rejected at creation; no side effects")
    void overdraftRejectedAtCreation() {
        String account = fundedAccount("50.00");

        postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "50.01", "AUD"))
                .then().statusCode(422)
                .body("error.code", equalTo("INSUFFICIENT_FUNDS"));

        assertThat(apiBalance(ALICE, account)).isEqualTo("50.00");
        assertThat(transactionCount(account)).as("no transaction row from the rejected request").isEqualTo(1);
        oracle().assertLedgerConsistent(account);
    }

    @Test
    @DisplayName("R-01 + contract 4.1: completion re-checks funds authoritatively; 422 keeps PROCESSING and is retriable")
    void completionRecheckIsAuthoritativeAndRetriable() {
        String account = fundedAccount("100.00");
        // Both withdrawals pass the advisory creation check against balance 100.00.
        String w1 = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "60.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        String w2 = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "60.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, w1, "PROCESSING").then().statusCode(200);
        transition(ALICE, w2, "PROCESSING").then().statusCode(200);

        transition(ALICE, w1, "COMPLETED").then().statusCode(200);
        assertThat(apiBalance(ALICE, account)).isEqualTo("40.00");

        // Authoritative check: only 40.00 left for the second 60.00 withdrawal.
        transition(ALICE, w2, "COMPLETED")
                .then().statusCode(422)
                .body("error.code", equalTo("INSUFFICIENT_FUNDS"));
        assertThat(dbTransactionStatus(w2)).as("contract 4.1: failed completion is not a transition").isEqualTo("PROCESSING");
        assertThat(apiBalance(ALICE, account)).isEqualTo("40.00");

        // Retriable after the balance becomes sufficient.
        createCompleted(account, "DEPOSIT", "20.00", "AUD");
        transition(ALICE, w2, "COMPLETED").then().statusCode(200);
        assertThat(apiBalance(ALICE, account)).isEqualTo("0.00");
        oracle().assertLedgerConsistent(account);
    }

    @Test
    @DisplayName("R-02: a FAILED transaction never modifies the balance")
    void failedTransactionHasNoBalanceEffect() {
        String account = fundedAccount("100.00");

        String withdrawal = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "30.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, withdrawal, "PROCESSING").then().statusCode(200);
        transition(ALICE, withdrawal, "FAILED").then().statusCode(200);

        String deposit = postTransaction(ALICE, account, newKey(), txBody("DEPOSIT", "500.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, deposit, "PROCESSING").then().statusCode(200);
        transition(ALICE, deposit, "FAILED").then().statusCode(200);

        assertThat(apiBalance(ALICE, account)).isEqualTo("100.00");
        oracle().assertLedgerConsistent(account);
    }

    @Test
    @DisplayName("R-03: a COMPLETED transaction changes the balance by exactly its amount, exactly once")
    void completedTransactionAppliesExactAmount() {
        String account = createAccount(ALICE, "AUD");
        createCompleted(account, "DEPOSIT", "100.00", "AUD");
        assertThat(apiBalance(ALICE, account)).isEqualTo("100.00");

        createCompleted(account, "WITHDRAWAL", "0.01", "AUD"); // minimum-amount boundary
        assertThat(apiBalance(ALICE, account)).isEqualTo("99.99");
        oracle().assertLedgerConsistent(account);
    }

    @Test
    @DisplayName("R-04: a completed transaction cannot apply its balance effect twice")
    void noDoubleApplicationOfBalanceEffect() {
        String account = createAccount(ALICE, "AUD");
        String deposit = createCompleted(account, "DEPOSIT", "100.00", "AUD");

        transition(ALICE, deposit, "COMPLETED")
                .then().statusCode(409)
                .body("error.code", equalTo("INVALID_STATE_TRANSITION"));

        assertThat(apiBalance(ALICE, account)).as("no double credit").isEqualTo("100.00");
        oracle().assertLedgerConsistent(account);
    }

    @Test
    @DisplayName("R-05: concurrent completions cannot double-spend; exactly one wins, balance never negative")
    void concurrentCompletionCannotDoubleSpend() {
        String account = fundedAccount("100.00");
        String w1 = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "80.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        String w2 = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "80.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, w1, "PROCESSING").then().statusCode(200);
        transition(ALICE, w2, "PROCESSING").then().statusCode(200);

        List<String> ids = List.of(w1, w2);
        AtomicInteger next = new AtomicInteger();
        List<Response> responses = inParallel(2, () ->
                transition(ALICE, ids.get(next.getAndIncrement()), "COMPLETED"));

        List<Integer> statusCodes = responses.stream().map(Response::statusCode).sorted().toList();
        assertThat(statusCodes).as("one completion succeeds, one is rejected").containsExactly(200, 422);
        long completed = ids.stream().filter(id -> dbTransactionStatus(id).equals("COMPLETED")).count();
        long processing = ids.stream().filter(id -> dbTransactionStatus(id).equals("PROCESSING")).count();
        assertThat(completed).as("exactly one of the two 80.00 withdrawals may complete").isEqualTo(1);
        assertThat(processing).as("the loser observably stays PROCESSING (contract 4.1)").isEqualTo(1);
        assertThat(apiBalance(ALICE, account)).isEqualTo("20.00");
        oracle().assertLedgerConsistent(account);
    }
}
