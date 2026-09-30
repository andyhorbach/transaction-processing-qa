package io.github.andyhorbach.txnqa.common;

public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Resource not found");
    }

    public static ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "Missing or invalid bearer token");
    }
}
