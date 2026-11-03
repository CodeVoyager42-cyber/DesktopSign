package com.example.signer.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignerExceptionTests {

    @Test
    void carriesErrorCodeAndMessage() {
        SignerException exception =
                new SignerException(ErrorCode.TOKEN_NOT_CONNECTED, "No token detected");

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOKEN_NOT_CONNECTED);
        assertThat(exception.getMessage()).isEqualTo("No token detected");
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void carriesCause() {
        Throwable cause = new IllegalStateException("root cause");

        SignerException exception =
                new SignerException(ErrorCode.PDF_INVALID, "PDF could not be parsed", cause);

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PDF_INVALID);
    }

    @Test
    void allRequiredErrorCodesExist() {
        assertThat(ErrorCode.values()).contains(
                ErrorCode.TOKEN_NOT_CONNECTED,
                ErrorCode.TOKEN_PIN_INCORRECT,
                ErrorCode.TOKEN_PIN_LOCKED,
                ErrorCode.TOKEN_REMOVED,
                ErrorCode.CERTIFICATE_EXPIRED,
                ErrorCode.PDF_SIGNING_FAILED,
                ErrorCode.SIGNATURE_VERIFICATION_FAILED);
    }
}