package io.github.andyhorbach.txnqa.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps all errors to the contract's envelope: {"error": {"code", "message"}}.
 * Messages never expose internal details (risk R-20 analogue: no stack traces in responses).
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    public record ErrorDetail(String code, String message) {
    }

    public record ErrorBody(ErrorDetail error) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> apiException(ApiException e) {
        return ResponseEntity.status(e.code().httpStatus())
                .body(new ErrorBody(new ErrorDetail(e.code().name(), e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400)
                .body(new ErrorBody(new ErrorDetail(ErrorCode.VALIDATION_ERROR.name(), "Malformed request body")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        // Framework-level client errors (unknown path -> 404, wrong method -> 405,
        // unsupported media type -> 415, ...) must stay controlled 4xx, never 500.
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            String code = status == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND.name() : status.name();
            return ResponseEntity.status(status)
                    .body(new ErrorBody(new ErrorDetail(code, status.getReasonPhrase())));
        }
        log.error("Unexpected error", e);
        return ResponseEntity.status(500)
                .body(new ErrorBody(new ErrorDetail("INTERNAL_ERROR", "Unexpected internal error")));
    }
}
