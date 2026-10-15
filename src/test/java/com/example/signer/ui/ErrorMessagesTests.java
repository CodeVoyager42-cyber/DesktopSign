package com.example.signer.ui;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.token.TokenException;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessagesTests {

    @Test
    void applicationMessagesAreShownAsWritten() {
        assertThat(ErrorMessages.generic(new SignerException(ErrorCode.WORKFLOW_INVALID,
                "The document was changed by someone else.")))
                .isEqualTo("The document was changed by someone else.");
        assertThat(ErrorMessages.generic(new TokenException(ErrorCode.TOKEN_PIN_INCORRECT, "x")))
                .isEqualTo("The PIN is incorrect. Please try again.");
    }

    @Test
    void rawJavaExceptionsAreNeverShown() {
        String io = ErrorMessages.generic(new IOException("C:\\\\Users\\\\x\\\\tmp: Access denied"));
        String npe = ErrorMessages.generic(new NullPointerException());
        assertThat(io).isEqualTo(ErrorMessages.UNEXPECTED).doesNotContain("Access denied");
        assertThat(npe).isEqualTo(ErrorMessages.UNEXPECTED).doesNotContain("NullPointer");
    }
}
