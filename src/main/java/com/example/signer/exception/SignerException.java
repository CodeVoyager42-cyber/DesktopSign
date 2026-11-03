package com.example.signer.exception;

/**
 * Base application exception.
 *
 * <p>Carries a stable {@link ErrorCode} so higher layers can react to errors
 * without string matching. Sensitive values must never be placed in the
 * message or cause chain.
 */
public class SignerException extends RuntimeException {

    private final ErrorCode errorCode;

    public SignerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SignerException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}