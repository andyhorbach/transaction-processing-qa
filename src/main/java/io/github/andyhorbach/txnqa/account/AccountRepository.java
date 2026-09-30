package io.github.andyhorbach.txnqa.account;

import io.github.andyhorbach.txnqa.common.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(UUID userId, Currency currency) {
        return jdbc.sql("""
                        INSERT INTO account (id, user_id, currency)
                        VALUES (:id, :userId, :currency)
                        RETURNING *
                        """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("currency", currency.name())
                .query(AccountRepository::map)
                .single();
    }

    /**
     * Ownership is part of the lookup itself (D-1): a foreign account is
     * indistinguishable from an absent one at this layer.
     */
    public Optional<Account> findOwned(UUID accountId, UUID userId) {
        return jdbc.sql("SELECT * FROM account WHERE id = :id AND user_id = :userId")
                .param("id", accountId)
                .param("userId", userId)
                .query(AccountRepository::map)
                .optional();
    }

    /**
     * The authoritative overdraft guard (R-01, R-05): check and debit are one
     * atomic statement, so concurrent completions cannot both pass.
     * Returns 0 when the balance is insufficient.
     */
    public int debitIfSufficient(UUID accountId, BigDecimal amount) {
        return jdbc.sql("""
                        UPDATE account
                        SET balance = balance - :amount
                        WHERE id = :id AND balance >= :amount
                        """)
                .param("id", accountId)
                .param("amount", amount)
                .update();
    }

    public void credit(UUID accountId, BigDecimal amount) {
        jdbc.sql("UPDATE account SET balance = balance + :amount WHERE id = :id")
                .param("id", accountId)
                .param("amount", amount)
                .update();
    }

    static Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Currency.valueOf(rs.getString("currency")),
                rs.getBigDecimal("balance"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
