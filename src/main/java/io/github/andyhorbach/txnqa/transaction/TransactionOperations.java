package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.account.AccountRepository;
import io.github.andyhorbach.txnqa.common.ApiException;
import io.github.andyhorbach.txnqa.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authoritative, atomic part of the lifecycle (contract sections 3.6 and 4.1).
 * Kept separate from the orchestrating service so @Transactional applies through
 * the proxy — and so the atomic unit is visibly one method.
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
