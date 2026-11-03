package com.example.signer.token;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;

/**
 * Error raised by the token layer.
 *
 * <p>Messages must never contain PINs, key material or other sensitive values.
 */
public class TokenException extends SignerException {

    public TokenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public TokenException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}