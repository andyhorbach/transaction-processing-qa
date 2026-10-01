package io.github.andyhorbach.txnqa.api;

import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * R-06: the full state-transition matrix — every (from, to) pair is attempted.
 * The 3 valid transitions succeed; the other 13 are rejected with 409 and
 * provably change nothing (DB status re-read, balance re-checked).
 *
 * The expected-valid set is hardcoded FROM THE CONTRACT (api-contract.md 3.6),
 * deliberately not derived from the application's TransactionStatus logic —
 * otherwise a bug in that logic would set the test's expectations too.
 */
class StateTransitionMatrixTest extends ApiTestBase {

    private static final List<String> STATUSES = List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED");
    private static final Set<String> CONTRACT_VALID = Set.of(
            "PENDING->PROCESSING",
            "PROCESSING->COMPLETED",
            "PROCESSING->FAILED");

    static Stream<Arguments> allTransitionPairs() {
        return STATUSES.stream().flatMap(from -> STATUSES.stream().map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "R-06: {0} -> {1}")
    @MethodSource("allTransitionPairs")
    void transitionPair(String from, String to) {
        String account = createAccount(ALICE, "AUD");
        String transactionId = depositInState(account, from);
        String balanceBefore = apiBalance(ALICE, account);
        boolean allowed = CONTRACT_VALID.contains(from + "->" + to);

        Response response = transition(ALICE, transactionId, to);

        if (allowed) {
            response.then().statusCode(200).body("status", equalTo(to));
            assertThat(dbTransactionStatus(transactionId)).isEqualTo(to);
        } else {
            response.then().statusCode(409).body("error.code", equalTo("INVALID_STATE_TRANSITION"));
            assertThat(dbTransactionStatus(transactionId))
                    .as("rejected transition must not change state")
                    .isEqualTo(from);
            assertThat(apiBalance(ALICE, account))
                    .as("rejected transition must not change the balance")
                    .isEqualTo(balanceBefore);
        }
        oracle().assertLedgerConsistent(account);
    }

    /** Builds a DEPOSIT on the account and drives it into the requested state via the API. */
    private String depositInState(String accountId, String state) {
        String id = postTransaction(ALICE, accountId, newKey(), txBody("DEPOSIT", "10.00", "AUD"))
                .then().statusCode(201).extract().path("id");
        switch (state) {
            case "PENDING" -> {
            }
            case "PROCESSING" -> transition(ALICE, id, "PROCESSING").then().statusCode(200);
            case "COMPLETED" -> {
                transition(ALICE, id, "PROCESSING").then().statusCode(200);
                transition(ALICE, id, "COMPLETED").then().statusCode(200);
            }
            case "FAILED" -> {
                transition(ALICE, id, "PROCESSING").then().statusCode(200);
                transition(ALICE, id, "FAILED").then().statusCode(200);
            }
            default -> throw new IllegalArgumentException(state);
        }
        return id;
    }
}
