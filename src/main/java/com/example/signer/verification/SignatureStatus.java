package com.example.signer.verification;

import java.util.Locale;

/**
 * The status shown for ONE signature, derived only from the verifier's real
 * result — never from the mere presence of a signature object.
 *
 * <p>Certificate trust and revocation are not checked by SignFlow (see
 * {@link SignatureProperties.TrustStatus#NOT_VERIFIED}), so there is no
 * "trusted" or "revoked" status here: the UI shows trust separately as
 * "not verified".
 */
public enum SignatureStatus {

    VALID("Valid signature",
            "The signature is cryptographically correct and the document has not"
                    + " been modified since it was signed."),
    MODIFIED_AFTER_SIGNING("Document modified after signing",
            "The signature itself is intact, but the document was changed"
                    + " afterwards."),
    CERTIFICATE_NOT_VALID_AT_SIGNING("Certificate not valid at signing time",
            "The signature is cryptographically correct, but the signing time is"
                    + " outside the certificate's validity period."),
    INVALID("Invalid signature",
            "The signature does not match the document or the signer's"
                    + " certificate."),
    SIGNATURE_ERROR("Signature could not be checked",
            "The signature data could not be read.");

    private final String title;
    private final String explanation;

    SignatureStatus(String title, String explanation) {
        this.title = title;
        this.explanation = explanation;
    }

    public String title() {
        return title;
    }

    public String explanation() {
        return explanation;
    }

    public boolean isValid() {
        return this == VALID;
    }

    /**
     * @param detail the signature's own verification detail
     * @param chain  the whole document's verification result
     */
    public static SignatureStatus of(SignatureVerificationResult.SignatureDetail detail,
                                     SignatureVerificationResult chain) {
        if (detail == null) {
            return SIGNATURE_ERROR;
        }
        if (!detail.valid()) {
            return detail.properties() == null ? SIGNATURE_ERROR : INVALID;
        }
        if (chain != null && !chain.valid()) {
            String message = chain.message() == null ? ""
                    : chain.message().toLowerCase(Locale.ROOT);
            return message.contains("modified") || message.contains("appended")
                    ? MODIFIED_AFTER_SIGNING : INVALID;
        }
        SignatureProperties p = detail.properties();
        if (p != null && Boolean.FALSE.equals(p.certValidAtSigningTime())) {
            return CERTIFICATE_NOT_VALID_AT_SIGNING;
        }
        return VALID;
    }
}
