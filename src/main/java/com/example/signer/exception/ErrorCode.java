package com.example.signer.exception;

/**
 * Stable, application-level error codes.
 *
 * <p>Used to keep errors machine-processable while keeping all human-readable
 * messages free of sensitive values (PINs, key material, credentials).
 */
public enum ErrorCode {

    TOKEN_NOT_CONNECTED,
    PKCS11_LIBRARY_NOT_FOUND,
    PKCS11_INITIALIZATION_FAILED,
    TOKEN_SESSION_FAILED,
    TOKEN_LOGIN_FAILED,
    TOKEN_PIN_INCORRECT,
    TOKEN_PIN_LOCKED,
    TOKEN_REMOVED,
    CERTIFICATE_NOT_FOUND,
    CERTIFICATE_EXPIRED,
    CERTIFICATE_NOT_YET_VALID,
    PRIVATE_KEY_NOT_AVAILABLE,
    UNSUPPORTED_ALGORITHM,
    PDF_INVALID,
    PDF_SIGNING_FAILED,
    SIGNATURE_VERIFICATION_FAILED
}