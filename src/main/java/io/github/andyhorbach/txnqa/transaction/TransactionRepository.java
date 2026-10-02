package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.common.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Transaction insertPending(UUID accountId, TransactionType type, BigDecimal amount,
                                     Currency currency, UUID originalTransactionId) {
        return jdbc.sql("""
                        INSERT INTO account_transaction (id, account_id, type, amount, currency, status, original_transaction_id)
                        VALUES (:id, :accountId, :type, :amount, :currency, :status, :originalId)
                        RETURNING *
                        """)
                .param("id", UUID.randomUUID())
                .param("accountId", accountId)
                .param("type", type.name())
                .param("amount", amount)
                .param("currency", currency.name())
                .param("status", TransactionStatus.PENDING.name())
                .param("originalId", originalTransactionId)
                .query(TransactionRepository::map)
                .single();
    }

    public Optional<Transaction> findById(UUID transactionId) {
        return jdbc.sql("SELECT * FROM account_transaction WHERE id = :id")
                .param("id", transactionId)
                .query(TransactionRepository::map)
                .optional();
    }

    /**
     * Ownership is resolved through the owning account (D-1): a foreign
     * transaction is indistinguishable from an absent one.
     */
    public Optional<Transaction> findOwned(UUID transactionId, UUID userId) {
        return jdbc.sql("""
                        SELECT t.*
                        FROM account_transaction t
                                 JOIN account a ON a.id = t.account_id
                        WHERE t.id = :id
                          AND a.user_id = :userId
                        """)
                .param("id", transactionId)
                .param("userId", userId)
                .query(TransactionRepository::map)
                .optional();
    }

    /**
     * Serializes concurrent refund completions against the same original (R-13, R-14).
     */
    public Optional<Transaction> lockById(UUID transactionId) {
        return jdbc.sql("SELECT * FROM account_transaction WHERE id = :id FOR UPDATE")
                .param("id", transactionId)
                .query(TransactionRepository::map)
                .optional();
    }

    /**
     * Serializes refund creations against the same original (D-9). Scoped to the
     * refunding account so another account's row is never locked.
     */
    public Optional<Transaction> lockOnAccount(UUID transactionId, UUID accountId) {
        return jdbc.sql("SELECT * FROM account_transaction WHERE id = :id AND account_id = :accountId FOR UPDATE")
                .param("id", transactionId)
                .param("accountId", accountId)
                .query(TransactionRepository::map)
                .optional();
    }

    public List<Transaction> listByAccount(UUID accountId) {
        return jdbc.sql("""
                        SELECT * FROM account_transaction
                        WHERE account_id = :accountId
                        ORDER BY created_at DESC, id
                        """)
                .param("accountId", accountId)
                .query(TransactionRepository::map)
                .list();
    }

    /**
     * Conditional on the expected current status: under concurrency only one
     * caller wins, which is the exactly-once guard for balance effects (R-04).
     */
    public int updateStatus(UUID transactionId, TransactionStatus from, TransactionStatus to) {
        return jdbc.sql("""
                        UPDATE account_transaction
                        SET status = :to
                        WHERE id = :id AND status = :from
                        """)
                .param("id", transactionId)
                .param("from", from.name())
                .param("to", to.name())
                .update();
    }

    /**
     * The refund cap counts all non-FAILED refunds, pending included (D-4).
     */
    public BigDecimal sumNonFailedRefunds(UUID originalTransactionId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0)
                        FROM account_transaction
                        WHERE original_transaction_id = :originalId
                          AND status <> 'FAILED'
                        """)
                .param("originalId", originalTransactionId)
                .query(BigDecimal.class)
                .single();
    }

    static Transaction map(ResultSet rs, int rowNum) throws SQLException {
        return new Transaction(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                TransactionType.valueOf(rs.getString("type")),
                rs.getBigDecimal("amount"),
                Currency.valueOf(rs.getString("currency")),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getObject("original_transaction_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
