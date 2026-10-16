package com.example.signer.client;

import com.example.signer.exception.ErrorCode;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenException;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.workflow.CertificateIdentity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * What the confirm screen says about the smart card / USB token, computed
 * WITHOUT a PIN: only the public certificates on the token are read. The
 * certificate shown is the real one found on the token; "matches" means its
 * SHA-256 fingerprint equals the one the workflow expects for this signer.
 * The signing step re-checks all of this before any signature is made.
 */
public record SigningCardCheck(State state, SigningCertificate certificate) {

    private static final Logger log = LoggerFactory.getLogger(SigningCardCheck.class);

    public enum State {
        /** PKCS#11 library not configured / not found on this computer. */
        NOT_CONFIGURED("Smart card software not set up",
                "SignFlow can't reach the smart card driver on this computer."
                        + " Ask your administrator to install it, then try again."),
        /** No token present. */
        NOT_DETECTED("Smart card required",
                "Insert your smart card or plug in your USB signing token,"
                        + " then click Check again."),
        /** Token present but it carries no signing certificate. */
        NO_CERTIFICATE("No signing certificate found",
                "The connected token has no certificate that can sign."),
        /** Token has certificates, none of them is the one this signer registered. */
        WRONG_CERTIFICATE("This is not the expected signing certificate",
                "The connected token doesn't hold the certificate registered for"
                        + " you in this signing request. Plug in the right token."),
        /** Matching certificate present. */
        READY("Signing certificate detected",
                "Your registered certificate is on the connected token."),
        /** Certificate present, but the workflow has no expected certificate to compare with. */
        PRESENT_UNCHECKED("Signing certificate detected",
                "A signing certificate is on the connected token. SignFlow checks"
                        + " it against this signing request when you sign."),
        /** The token could not be read. */
        ERROR("The token could not be read",
                "Reconnect the token and click Check again.");

        private final String title;
        private final String explanation;

        State(String title, String explanation) {
            this.title = title;
            this.explanation = explanation;
        }

        public String title() {
            return title;
        }

        public String explanation() {
            return explanation;
        }

        /** True when signing can be attempted. */
        public boolean canSign() {
            return this == READY || this == PRESENT_UNCHECKED;
        }
    }

    /** Detects the token and compares its certificates with {@code required}. */
    public static SigningCardCheck run(TokenService tokens, CertificateIdentity required) {
        try {
            tokens.detect();
            if (tokens.getState() == TokenState.NO_TOKEN) {
                return new SigningCardCheck(State.NOT_DETECTED, null);
            }
            return evaluate(tokens.getCertificates(), required);
        } catch (TokenException e) {
            if (e.getErrorCode() == ErrorCode.PKCS11_LIBRARY_NOT_FOUND
                    || e.getErrorCode() == ErrorCode.PKCS11_INITIALIZATION_FAILED) {
                return new SigningCardCheck(State.NOT_CONFIGURED, null);
            }
            if (e.getErrorCode() == ErrorCode.TOKEN_NOT_CONNECTED
                    || e.getErrorCode() == ErrorCode.TOKEN_REMOVED) {
                return new SigningCardCheck(State.NOT_DETECTED, null);
            }
            log.warn("Token check failed ({})", e.getErrorCode());
            return new SigningCardCheck(State.ERROR, null);
        } catch (RuntimeException e) {
            log.warn("Token check failed", e);
            return new SigningCardCheck(State.ERROR, null);
        }
    }

    /** Pure comparison (no token access). */
    static SigningCardCheck evaluate(List<SigningCertificate> certificates,
                                     CertificateIdentity required) {
        if (certificates == null || certificates.isEmpty()) {
            return new SigningCardCheck(State.NO_CERTIFICATE, null);
        }
        if (required == null || !required.isBound()) {
            return new SigningCardCheck(State.PRESENT_UNCHECKED,
                    certificates.size() == 1 ? certificates.get(0) : null);
        }
        for (SigningCertificate certificate : certificates) {
            if (required.matchesFingerprint(certificate.getSha256Fingerprint())) {
                return new SigningCardCheck(State.READY, certificate);
            }
        }
        return new SigningCardCheck(State.WRONG_CERTIFICATE, null);
    }
}
