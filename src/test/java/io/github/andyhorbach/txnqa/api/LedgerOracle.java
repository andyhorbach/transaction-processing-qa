package io.github.andyhorbach.txnqa.api;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent database oracle for R-18/R-19.
 *
 * Recomputes the expected balance of an account from its COMPLETED transaction
 * history according to the CONTRACT's balance-effect table (api-contract.md
 * section 4): DEPOSIT/REFUND credit, WITHDRAWAL/FEE debit.
 *
 * This is deliberately NOT the application's algorithm — the application never
 * aggregates history; it mutates the balance incrementally with conditional
 * updates. A bug in that incremental logic (double apply, missed rollback,
 * drift) therefore cannot cancel out here.
 */
public class LedgerOracle {

    private final JdbcClient jdbc;

    public LedgerOracle(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal recomputedBalance(UUID accountId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE
                                                WHEN type IN ('DEPOSIT', 'REFUND') THEN amount
                                                WHEN type IN ('WITHDRAWAL', 'FEE') THEN -amount
                            END), 0)
                        FROM account_transaction
                        WHERE account_id = :accountId
                          AND status = 'COMPLETED'
                        """)
                .param("accountId", accountId)
                .query(BigDecimal.class)
                .single();
    }

    public BigDecimal persistedBalance(UUID accountId) {
        return jdbc.sql("SELECT balance FROM account WHERE id = :accountId")
                .param("accountId", accountId)
                .query(BigDecimal.class)
                .single();
    }

    /**
     * R-19: the stored balance and the balance recomputed from history must
     * agree — at any observable moment, under any concurrency.
     */
    public void assertLedgerConsistent(String accountId) {
        UUID id = UUID.fromString(accountId);
        BigDecimal persisted = persistedBalance(id);
        BigDecimal recomputed = recomputedBalance(id);
        assertThat(persisted)
                .as("R-19: persisted balance must equal the balance recomputed from COMPLETED history")
                .isEqualByComparingTo(recomputed);
        assertThat(persisted)
                .as("balance must never be negative")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
