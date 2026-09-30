package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.account.Account;
import io.github.andyhorbach.txnqa.account.AccountService;
import io.github.andyhorbach.txnqa.auth.AuthenticatedUser;
import io.github.andyhorbach.txnqa.common.ApiException;
import io.github.andyhorbach.txnqa.common.Currency;
import io.github.andyhorbach.txnqa.common.ErrorCode;
import io.github.andyhorbach.txnqa.common.Ids;
import io.github.andyhorbach.txnqa.common.Money;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    public record CreateResult(Transaction transaction, boolean replayed) {
    }

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final TransactionOperations transactionOperations;
    private final IdempotencyService idempotencyService;

    public TransactionService(AccountService accountService,
                              TransactionRepository transactionRepository,
                              TransactionOperations transactionOperations,
                              IdempotencyService idempotencyService) {
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
        this.transactionOperations = transactionOperations;
        this.idempotencyService = idempotencyService;
    }

    public CreateResult create(AuthenticatedUser user, String rawAccountId, String idempotencyKey,
                               TransactionController.CreateTransactionRequest request) {
        Account account = accountService.getOwned(user, rawAccountId);
        validateIdempotencyKey(idempotencyKey);

        TransactionType type = TransactionType.parse(request.type());
        BigDecimal amount = Money.parseAmount(request.amount());
        Currency currency = Currency.parse(request.currency());
        UUID originalId = resolveOriginalTransactionId(type, request.originalTransactionId());

        String hash = IdempotencyService.requestHash(
                account.id(), type, Money.format(amount), currency.name(), originalId);
        IdempotencyService.BeginResult begin = idempotencyService.begin(user.id(), idempotencyKey, hash);
        switch (begin.outcome()) {
            case PAYLOAD_MISMATCH -> throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSE,
                    "Idempotency key was already used with a different payload");
            case IN_FLIGHT -> throw new ApiException(ErrorCode.DUPLICATE_REQUEST_IN_PROGRESS,
                    "A request with this idempotency key is currently being processed");
            case REPLAY -> {
                Transaction stored = transactionRepository.findById(begin.transactionId()).orElseThrow();
                return new CreateResult(stored, true);
            }
            case NEW -> {
                // fall through to creation below
            }
        }

        try {
            runAdvisoryChecks(account, type, amount, currency, originalId);
            Transaction created = transactionRepository.insertPending(
                    account.id(), type, amount, currency, originalId);
            idempotencyService.complete(user.id(), idempotencyKey, created.id());
            return new CreateResult(created, false);
        } catch (RuntimeException e) {
            idempotencyService.release(user.id(), idempotencyKey);
            throw e;
        }
    }

    public Transaction getOwned(AuthenticatedUser user, String rawTransactionId) {
        UUID transactionId = Ids.parsePathId(rawTransactionId);
        return transactionRepository.findOwned(transactionId, user.id())
                .orElseThrow(ApiException::notFound);
    }

    public List<Transaction> listForAccount(AuthenticatedUser user, String rawAccountId) {
        Account account = accountService.getOwned(user, rawAccountId);
        return transactionRepository.listByAccount(account.id());
    }

    public Transaction transition(AuthenticatedUser user, String rawTransactionId, String rawTo) {
        Transaction transaction = getOwned(user, rawTransactionId);
        TransactionStatus to = TransactionStatus.parse(rawTo);
        if (!TransactionStatus.isValidTransition(transaction.status(), to)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Transition " + transaction.status() + " -> " + to + " is not allowed");
        }
        return transactionOperations.applyTransition(transaction, to);
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.validation("Idempotency-Key header is required");
        }
        if (key.length() > 64) {
            throw ApiException.validation("Idempotency-Key must be at most 64 characters");
        }
    }

    private static UUID resolveOriginalTransactionId(TransactionType type, String raw) {
        if (type == TransactionType.REFUND) {
            if (raw == null) {
                throw ApiException.validation("original_transaction_id is required for REFUND");
            }
            return Ids.parseBodyId(raw, "original_transaction_id");
        }
        if (raw != null) {
            throw ApiException.validation("original_transaction_id is only allowed for REFUND");
        }
        return null;
    }

    /**
     * Advisory checks (contract section 4): fast feedback at creation; the
     * authoritative equivalents run atomically at completion in TransactionOperations.
     */
    private void runAdvisoryChecks(Account account, TransactionType type, BigDecimal amount,
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
            validateRefundEligibility(account, originalId, amount);
        }
    }

    private void validateRefundEligibility(Account account, UUID originalId, BigDecimal amount) {
        Transaction original = transactionRepository.findById(originalId)
                .filter(t -> t.accountId().equals(account.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.REFUND_NOT_ALLOWED,
                        "Original transaction is not refundable"));
        if (original.status() != TransactionStatus.COMPLETED || !original.type().isRefundable()) {
            throw new ApiException(ErrorCode.REFUND_NOT_ALLOWED,
                    "Original transaction is not refundable");
        }
        BigDecimal remaining = original.amount()
                .subtract(transactionRepository.sumNonFailedRefunds(originalId));
        if (amount.compareTo(remaining) > 0) {
            throw new ApiException(ErrorCode.REFUND_EXCEEDS_ORIGINAL,
                    "Refund amount exceeds the remaining refundable amount");
        }
    }
}
