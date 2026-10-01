package io.github.andyhorbach.txnqa.api;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Authorization isolation — risks R-16/R-17 (risk-analysis.md 2.6) and the
 * D-1 non-disclosure rule: a foreign resource must be indistinguishable from
 * an absent one, for reads and writes alike.
 */
class AuthorizationTest extends ApiTestBase {

    enum CrossUserOperation {
        READ_ACCOUNT,
        LIST_TRANSACTIONS,
        READ_TRANSACTION,
        CREATE_TRANSACTION,
        TRANSITION_TRANSACTION
    }

    @ParameterizedTest(name = "R-16/R-17: {0} on a foreign resource -> 404")
    @EnumSource(CrossUserOperation.class)
    void crossUserAccessIsNotFound(CrossUserOperation operation) {
        String aliceAccount = fundedAccount("50.00");
        String aliceTransaction = postTransaction(ALICE, aliceAccount, newKey(), txBody("WITHDRAWAL", "10.00", "AUD"))
                .then().statusCode(201).extract().path("id");

        Response response = switch (operation) {
            case READ_ACCOUNT -> auth(BOB).get("/accounts/{id}", aliceAccount);
            case LIST_TRANSACTIONS -> auth(BOB).get("/accounts/{id}/transactions", aliceAccount);
            case READ_TRANSACTION -> auth(BOB).get("/transactions/{id}", aliceTransaction);
            case CREATE_TRANSACTION ->
                    postTransaction(BOB, aliceAccount, newKey(), txBody("DEPOSIT", "1.00", "AUD"));
            case TRANSITION_TRANSACTION -> transition(BOB, aliceTransaction, "PROCESSING");
        };

        response.then().statusCode(404).body("error.code", equalTo("NOT_FOUND"));
        assertThat(dbTransactionStatus(aliceTransaction))
                .as("R-17: the foreign write attempt must have no effect")
                .isEqualTo("PENDING");
        assertThat(transactionCount(aliceAccount))
                .as("R-17: no transaction created by the foreign user")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("D-1: a foreign resource is byte-for-byte indistinguishable from an absent one")
    void foreignAndAbsentResourcesAreIndistinguishable() {
        String aliceAccount = createAccount(ALICE, "AUD");

        String foreignBody = auth(BOB).get("/accounts/{id}", aliceAccount)
                .then().statusCode(404).extract().asString();
        String absentBody = auth(BOB).get("/accounts/{id}", UUID.randomUUID().toString())
                .then().statusCode(404).extract().asString();

        assertThat(foreignBody)
                .as("D-1: identical body for foreign and absent resources — no existence disclosure")
                .isEqualTo(absentBody);
    }

    @Test
    @DisplayName("R-16: requests without a valid bearer token are rejected with 401")
    void unauthenticatedRequestsRejected() {
        String account = createAccount(ALICE, "AUD");
        noAuth().get("/accounts/{id}", account)
                .then().statusCode(401).body("error.code", equalTo("UNAUTHORIZED"));
        auth("not-a-real-token").get("/accounts/{id}", account)
                .then().statusCode(401).body("error.code", equalTo("UNAUTHORIZED"));
    }
}
