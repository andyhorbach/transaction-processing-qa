package io.github.andyhorbach.txnqa.common;

/**
 * Error codes defined in docs/api-contract.md section 6.
 */
public enum ErrorCode {
    VALIDATION_ERROR(400),
    UNAUTHORIZED(401),
    NOT_FOUND(404),
    INVALID_STATE_TRANSITION(409),
    IDEMPOTENCY_KEY_REUSE(409),
    DUPLICATE_REQUEST_IN_PROGRESS(409),
    INSUFFICIENT_FUNDS(422),
    CURRENCY_MISMATCH(422),
    REFUND_NOT_ALLOWED(422),
    REFUND_EXCEEDS_ORIGINAL(422);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
