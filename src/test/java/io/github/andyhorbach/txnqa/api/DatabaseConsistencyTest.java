package io.github.andyhorbach.txnqa.api;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database consistency — risks R-18/R-19 (risk-analysis.md 2.7).
 *
 * R-18: the API must report exactly what the database holds.
 * R-19: the stored balance must equal the balance recomputed from COMPLETED
 * history — asserted via LedgerOracle, with the expected end balance in the
 * mixed scenario computed BY HAND from the contract's balance-effect rules,
 * independent of both the application and the oracle.
 *
 * R-20 (partial update on mid-operation failure) requires fault injection
 * inside the database transaction and is deliberately not covered by this
 * black-box suite; it is a documented gap, not an oversight.
 */
class DatabaseConsistencyTest extends ApiTestBase {

    @Test
    @DisplayName("R-18: transaction API responses agree with the database row, before and after completion")
    void transactionApiAgreesWithDatabase() {
        String account = fundedAccount("100.00");
        Response created = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "30.00", "AUD"));
        created.then().statusCode(201);
        String id = created.path("id");

        assertApiMatchesDbRow(id, created);

        transition(ALICE, id, "PROCESSING").then().statusCode(200);
        transition(ALICE, id, "COMPLETED").then().statusCode(200);
        Response fetched = auth(ALICE).get("/transactions/{id}", id);
        fetched.then().statusCode(200);
        assertApiMatchesDbRow(id, fetched);
    }

    @Test
    @DisplayName("R-18: the account balance reported by the API equals the persisted balance")
    void accountBalanceApiAgreesWithDatabase() {
        String account = fundedAccount("100.00");
        createCompleted(account, "WITHDRAWAL", "17.50", "AUD");

        String apiBalance = apiBalance(ALICE, account);
        BigDecimal dbBalance = oracle().persistedBalance(UUID.fromString(account));
        assertThat(new BigDecimal(apiBalance)).isEqualByComparingTo(dbBalance);
    }

    @Test
    @DisplayName("R-19: after a mixed history the balance equals the hand-computed contract result")
    void mixedHistoryMatchesHandComputedBalance() {
        String account = createAccount(ALICE, "AUD");
        createCompleted(account, "DEPOSIT", "100.00", "AUD");
        String withdrawal = createCompleted(account, "WITHDRAWAL", "30.00", "AUD");

        // A FAILED withdrawal: must contribute nothing.
        String failed = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "20.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, failed, "PROCESSING").then().statusCode(200);
        transition(ALICE, failed, "FAILED").then().statusCode(200);

        createCompleted(account, "FEE", "10.00", "AUD");

        String refund = postTransaction(ALICE, account, newKey(), refundBody("5.00", "AUD", withdrawal))
                .then().statusCode(201).extract().path("id");
        transition(ALICE, refund, "PROCESSING").then().statusCode(200);
        transition(ALICE, refund, "COMPLETED").then().statusCode(200);

        // By hand, per contract section 4: +100.00 -30.00 (FAILED: 0) -10.00 +5.00 = 65.00
        assertThat(apiBalance(ALICE, account)).isEqualTo("65.00");
        assertThat(oracle().persistedBalance(UUID.fromString(account)))
                .isEqualByComparingTo(new BigDecimal("65.00"));
        oracle().assertLedgerConsistent(account);
    }

    private void assertApiMatchesDbRow(String transactionId, Response apiResponse) {
        Map<String, Object> row = jdbc.sql("SELECT * FROM account_transaction WHERE id = :id")
                .param("id", UUID.fromString(transactionId))
                .query().singleRow();
        assertThat(apiResponse.<String>path("status")).isEqualTo(row.get("status"));
        assertThat(apiResponse.<String>path("type")).isEqualTo(row.get("type"));
        assertThat(apiResponse.<String>path("currency")).isEqualTo(row.get("currency"));
        assertThat(apiResponse.<String>path("account_id")).isEqualTo(row.get("account_id").toString());
        assertThat(new BigDecimal(apiResponse.<String>path("amount")))
                .isEqualByComparingTo((BigDecimal) row.get("amount"));
    }
}
