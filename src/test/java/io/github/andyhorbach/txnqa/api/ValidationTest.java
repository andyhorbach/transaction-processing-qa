package io.github.andyhorbach.txnqa.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Currency and monetary precision — risks R-10…R-12 (risk-analysis.md 2.4),
 * plus the input-contract rules of api-contract.md sections 1 and 3.4.
 */
class ValidationTest extends ApiTestBase {

    @Test
    @DisplayName("R-10: transaction currency must match the account currency")
    void currencyMismatchRejected() {
        String account = createAccount(ALICE, "AUD");
        postTransaction(ALICE, account, newKey(), txBody("DEPOSIT", "10.00", "USD"))
                .then().statusCode(422)
                .body("error.code", equalTo("CURRENCY_MISMATCH"));
        assertThat(transactionCount(account)).isZero();
    }

    @Test
    @DisplayName("R-10: unsupported currency codes are rejected on input (D-2)")
    void unsupportedCurrencyRejected() {
        String account = createAccount(ALICE, "AUD");
        postTransaction(ALICE, account, newKey(), txBody("DEPOSIT", "10.00", "XXX"))
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
        authJson(ALICE).body("{\"currency\":\"BTC\"}").post("/accounts")
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("R-11: 0.10 + 0.20 is exactly 0.30 — no binary floating point in the money path")
    void floatTrapAmountsAreExact() {
        String account = createAccount(ALICE, "AUD");
        createCompleted(account, "DEPOSIT", "0.10", "AUD");
        createCompleted(account, "DEPOSIT", "0.20", "AUD");
        assertThat(apiBalance(ALICE, account)).isEqualTo("0.30");
        oracle().assertLedgerConsistent(account);
    }

    @ParameterizedTest(name = "R-12 / R-22 / D-10: amount \"{0}\" is rejected with 400")
    @ValueSource(strings = {"10.001", "0.00", "0", "-5.00", "1,50", "abc", "", "1e2", "00.10",
            "1000000.01", "123456789012345678901.00"})
    void invalidAmountsRejected(String amount) {
        String account = createAccount(ALICE, "AUD");
        postTransaction(ALICE, account, newKey(), txBody("DEPOSIT", amount, "AUD"))
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
        assertThat(transactionCount(account)).isZero();
    }

    @ParameterizedTest(name = "R-22 / D-10: non-string amount {0} is rejected with 400")
    @ValueSource(strings = {"10.5", "10", "true", "null", "{}", "[]"})
    void nonStringAmountsRejected(String rawJsonAmount) {
        String account = createAccount(ALICE, "AUD");
        String body = "{\"type\":\"DEPOSIT\",\"amount\":%s,\"currency\":\"AUD\"}".formatted(rawJsonAmount);
        postTransaction(ALICE, account, newKey(), body)
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
        assertThat(transactionCount(account)).isZero();
    }

    @Test
    @DisplayName("D-10: the maximum 1000000.00 is accepted inclusively and returned normalized")
    void maximumAmountAcceptedAndNormalized() {
        String account = createAccount(ALICE, "AUD");
        postTransaction(ALICE, account, newKey(), txBody("DEPOSIT", "1000000", "AUD"))
                .then().statusCode(201)
                .body("amount", equalTo("1000000.00"));
    }

    @Test
    @DisplayName("R-12: database column scale is 2, identical to the API contract (D-3)")
    void databaseScaleMatchesContract() {
        Integer balanceScale = jdbc.sql("""
                        SELECT numeric_scale FROM information_schema.columns
                        WHERE table_name = 'account' AND column_name = 'balance'
                        """).query(Integer.class).single();
        Integer amountScale = jdbc.sql("""
                        SELECT numeric_scale FROM information_schema.columns
                        WHERE table_name = 'account_transaction' AND column_name = 'amount'
                        """).query(Integer.class).single();
        assertThat(balanceScale).isEqualTo(2);
        assertThat(amountScale).isEqualTo(2);
    }

    @Test
    @DisplayName("Contract 3.4: Idempotency-Key is required and at most 64 characters")
    void idempotencyKeyRules() {
        String account = createAccount(ALICE, "AUD");
        postTransaction(ALICE, account, null, txBody("DEPOSIT", "10.00", "AUD"))
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
        postTransaction(ALICE, account, "k".repeat(65), txBody("DEPOSIT", "10.00", "AUD"))
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Contract 1: malformed JSON body -> controlled 400, never a 5xx")
    void malformedBodyRejected() {
        String account = createAccount(ALICE, "AUD");
        authJson(ALICE).header("Idempotency-Key", newKey()).body("this is not json")
                .post("/accounts/{accountId}/transactions", account)
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Contract 3.4: type must be a supported enum; original_transaction_id only for REFUND")
    void typeAndOriginalFieldRules() {
        String account = createAccount(ALICE, "AUD");
        postTransaction(ALICE, account, newKey(), txBody("TRANSFER", "10.00", "AUD"))
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
        postTransaction(ALICE, account, newKey(),
                "{\"type\":\"DEPOSIT\",\"amount\":\"10.00\",\"currency\":\"AUD\",\"original_transaction_id\":\"%s\"}"
                        .formatted(newKey()))
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
        postTransaction(ALICE, account, newKey(), "{\"type\":\"REFUND\",\"amount\":\"10.00\",\"currency\":\"AUD\"}")
                .then().statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }
}
