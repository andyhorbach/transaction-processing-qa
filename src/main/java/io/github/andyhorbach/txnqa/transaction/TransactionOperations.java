package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.account.Account;
import io.github.andyhorbach.txnqa.account.AccountRepository;
import io.github.andyhorbach.txnqa.common.ApiException;
import io.github.andyhorbach.txnqa.common.Currency;
import io.github.andyhorbach.txnqa.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The atomic units of the lifecycle: transaction creation (contract section 3.4)
 * and transitions (sections 3.6 and 4.1). Kept separate from the orchestrating
 * service so @Transactional applies through the proxy — and so each atomic unit
 * is visibly one method.
 */
@Service
public class TransactionOperations {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TransactionOperations(TransactionRepository transactionRepository,
                                 AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Creation checks and the insert run in one database transaction. Currency and
     * funds checks are advisory (contract section 4). The refund cap is the exception:
     * it is authoritative here (D-9). The original is locked first, so concurrent
     * refunds against it serialize; the refund sum is then read by a separate
     * statement, which under READ COMMITTED sees refunds committed while waiting.
     */
    @Transactional
    public Transaction createPending(Account account, TransactionType type, BigDecimal amount,
                                     Currency currency, UUID originalId) {
        if (currency != account.currency()) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH,
                    "Transaction currency does not match the account currency");
        }
        if (type.isDebit() && amount.compareTo(account.balance()) > 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Balance is insufficient for this transaction");
        }
        if (type == TransactionType.REFUND) {
            enforceRefundCap(account, originalId, amount);
        }
        return transactionRepository.insertPending(account.id(), type, amount, currency, originalId);
    }

    /**
     * Absent, foreign, and ineligible originals produce the identical error,
     * so the response never reveals whether another account's transaction exists (D-1).
     */
    private void enforceRefundCap(Account account, UUID originalId, BigDecimal amount) {
        Transaction original = transactionRepository.lockOnAccount(originalId, account.id())
                .orElseThrow(TransactionOperations::refundNotAllowed);
        if (original.status() != TransactionStatus.COMPLETED || !original.type().isRefundable()) {
            throw refundNotAllowed();
        }
        BigDecimal remaining = original.amount()
                .subtract(transactionRepository.sumNonFailedRefunds(originalId));
        if (amount.compareTo(remaining) > 0) {
            throw new ApiException(ErrorCode.REFUND_EXCEEDS_ORIGINAL,
                    "Refund amount exceeds the remaining refundable amount");
        }
    }

    private static ApiException refundNotAllowed() {
        return new ApiException(ErrorCode.REFUND_NOT_ALLOWED, "Original transaction is not refundable");
    }

    /**
     * Status change and balance effect are one database transaction: a 422 at
     * completion rolls the status change back, so the transaction observably
     * "remains PROCESSING" exactly as contract section 4.1 requires.
     */
    @Transactional
    public Transaction applyTransition(Transaction transaction, TransactionStatus to) {
        int updated = transactionRepository.updateStatus(transaction.id(), transaction.status(), to);
        if (updated == 0) {
            // Lost a race: someone else moved the status first. Exactly-once guard (R-04).
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Transition " + transaction.status() + " -> " + to + " is not allowed");
        }
        if (to == TransactionStatus.COMPLETED) {
            applyBalanceEffect(transaction);
        }
        return transactionRepository.findById(transaction.id()).orElseThrow();
    }

    private void applyBalanceEffect(Transaction transaction) {
        switch (transaction.type()) {
            case DEPOSIT -> accountRepository.credit(transaction.accountId(), transaction.amount());
            case WITHDRAWAL, FEE -> {
                if (accountRepository.debitIfSufficient(transaction.accountId(), transaction.amount()) == 0) {
                    throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                            "Balance is insufficient to complete this transaction");
                }
            }
            case REFUND -> {
                // Second safeguard behind the creation-time cap (D-9); not expected to fire.
                Transaction original = transactionRepository.lockById(transaction.originalTransactionId())
                        .orElseThrow(ApiException::notFound);
                if (transactionRepository.sumNonFailedRefunds(original.id()).compareTo(original.amount()) > 0) {
                    throw new ApiException(ErrorCode.REFUND_EXCEEDS_ORIGINAL,
                            "Refunds against the original transaction exceed its amount");
                }
                accountRepository.credit(transaction.accountId(), transaction.amount());
            }
        }
    }
}
