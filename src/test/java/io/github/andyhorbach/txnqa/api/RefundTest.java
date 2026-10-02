package io.github.andyhorbach.txnqa.api;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Refunds — risks R-13…R-15 (risk-analysis.md 2.5) and contract decisions
 * D-4 (partial refunds, pending counted) and D-7 (eligibility + same account).
 */
class RefundTest extends ApiTestBase {

    /** account funded with 100.00, with a COMPLETED 40.00 withdrawal to refund against. */
    private record Fixture(String accountId, String withdrawalId) {
    }

    private Fixture originalWithdrawal() {
        String account = fundedAccount("100.00");
        String withdrawal = createCompleted(account, "WITHDRAWAL", "40.00", "AUD");
        return new Fixture(account, withdrawal);
    }

    @Test
    @DisplayName("R-13: a refund exceeding the original amount is rejected")
    void overRefundRejected() {
        Fixture fx = originalWithdrawal();
        postTransaction(ALICE, fx.accountId(), newKey(), refundBody("40.01", "AUD", fx.withdrawalId()))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_EXCEEDS_ORIGINAL"));
    }

    @Test
    @DisplayName("R-13/R-14 + D-4: partial refunds are capped by their sum; the cap is exact")
    void partialRefundsCappedBySum() {
        Fixture fx = originalWithdrawal();
        String r1 = postTransaction(ALICE, fx.accountId(), newKey(), refundBody("25.00", "AUD", fx.withdrawalId()))
                .then().statusCode(201).extract().path("id");
        completeViaApi(r1);
        assertThat(apiBalance(ALICE, fx.accountId())).isEqualTo("85.00");

        String r2 = postTransaction(ALICE, fx.accountId(), newKey(), refundBody("15.00", "AUD", fx.withdrawalId()))
                .then().statusCode(201).extract().path("id");
        completeViaApi(r2);
        assertThat(apiBalance(ALICE, fx.accountId())).isEqualTo("100.00");

        postTransaction(ALICE, fx.accountId(), newKey(), refundBody("0.01", "AUD", fx.withdrawalId()))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_EXCEEDS_ORIGINAL"));
        oracle().assertLedgerConsistent(fx.accountId());
    }

    @Test
    @DisplayName("D-4: pending refunds count toward the cap")
    void pendingRefundsCountTowardCap() {
        Fixture fx = originalWithdrawal();
        // 30.00 stays PENDING — deliberately not completed.
        postTransaction(ALICE, fx.accountId(), newKey(), refundBody("30.00", "AUD", fx.withdrawalId()))
                .then().statusCode(201);

        postTransaction(ALICE, fx.accountId(), newKey(), refundBody("10.01", "AUD", fx.withdrawalId()))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_EXCEEDS_ORIGINAL"));

        postTransaction(ALICE, fx.accountId(), newKey(), refundBody("10.00", "AUD", fx.withdrawalId()))
                .then().statusCode(201);
    }

    @Test
    @DisplayName("R-14/R-23 + D-9: of 5 concurrent full refunds exactly one is created, and it completes")
    void concurrentFullRefundsCreateExactlyOneThatCompletes() {
        Fixture fx = originalWithdrawal();
        List<Response> creations = inParallel(5, () ->
                postTransaction(ALICE, fx.accountId(), newKey(), refundBody("40.00", "AUD", fx.withdrawalId())));

        List<Response> created = creations.stream().filter(r -> r.statusCode() == 201).toList();
        List<Response> rejected = creations.stream().filter(r -> r.statusCode() != 201).toList();
        assertThat(created).as("D-9: the cap is authoritative at creation — exactly one full refund fits").hasSize(1);
        assertThat(rejected)
                .as("D-9: every other concurrent full refund is rejected at creation")
                .hasSize(4)
                .allSatisfy(r -> {
                    assertThat(r.statusCode()).isEqualTo(422);
                    assertThat(r.<String>path("error.code")).isEqualTo("REFUND_EXCEEDS_ORIGINAL");
                });

        String refund = created.getFirst().path("id");
        transition(ALICE, refund, "PROCESSING").then().statusCode(200);
        transition(ALICE, refund, "COMPLETED").then().statusCode(200);

        BigDecimal completedRefunds = jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM account_transaction
                        WHERE original_transaction_id = :originalId AND status = 'COMPLETED'
                        """)
                .param("originalId", UUID.fromString(fx.withdrawalId()))
                .query(BigDecimal.class).single();
        assertThat(completedRefunds).as("exactly the original amount was refunded").isEqualByComparingTo("40.00");
        Long unfinishedRefunds = jdbc.sql("""
                        SELECT COUNT(*) FROM account_transaction
                        WHERE original_transaction_id = :originalId AND status IN ('PENDING', 'PROCESSING')
                        """)
                .param("originalId", UUID.fromString(fx.withdrawalId()))
                .query(Long.class).single();
        assertThat(unfinishedRefunds).as("R-23: no accepted refund is left unable to complete").isZero();
        assertThat(apiBalance(ALICE, fx.accountId())).isEqualTo("100.00");
        oracle().assertLedgerConsistent(fx.accountId());
    }

    @Test
    @DisplayName("Contract 4 (refund scope): the original must be on the same account, not merely the same user")
    void refundMustTargetSameAccount() {
        Fixture fx = originalWithdrawal();
        String otherAliceAccount = fundedAccount("10.00");

        postTransaction(ALICE, otherAliceAccount, newKey(), refundBody("5.00", "AUD", fx.withdrawalId()))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("D-7: credits, non-COMPLETED, foreign, and absent originals are not refundable")
    void nonRefundableOriginals() {
        String account = fundedAccount("100.00");
        String deposit = createCompleted(account, "DEPOSIT", "20.00", "AUD");
        postTransaction(ALICE, account, newKey(), refundBody("5.00", "AUD", deposit))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_NOT_ALLOWED"));

        String pendingWithdrawal = postTransaction(ALICE, account, newKey(), txBody("WITHDRAWAL", "10.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        postTransaction(ALICE, account, newKey(), refundBody("5.00", "AUD", pendingWithdrawal))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_NOT_ALLOWED"));

        String bobAccount = createAccount(BOB, "AUD");
        String bobDeposit = postTransaction(BOB, bobAccount, newKey(), txBody("DEPOSIT", "30.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        postTransaction(ALICE, account, newKey(), refundBody("5.00", "AUD", bobDeposit))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_NOT_ALLOWED"));

        postTransaction(ALICE, account, newKey(), refundBody("5.00", "AUD", UUID.randomUUID().toString()))
                .then().statusCode(422)
                .body("error.code", equalTo("REFUND_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("R-15: refund completion keeps refund row, balance, and history mutually consistent")
    void refundPreservesLedgerConsistency() {
        Fixture fx = originalWithdrawal();
        String refund = postTransaction(ALICE, fx.accountId(), newKey(), refundBody("25.00", "AUD", fx.withdrawalId()))
                .then().statusCode(201).extract().path("id");
        completeViaApi(refund);

        assertThat(dbTransactionStatus(refund)).isEqualTo("COMPLETED");
        assertThat(apiBalance(ALICE, fx.accountId())).isEqualTo("85.00");
        oracle().assertLedgerConsistent(fx.accountId());
    }

    private void completeViaApi(String transactionId) {
        transition(ALICE, transactionId, "PROCESSING").then().statusCode(200);
        transition(ALICE, transactionId, "COMPLETED").then().statusCode(200);
    }
}
