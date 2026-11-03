package com.example.signer.verification;

import java.nio.file.Path;

/**
 * Verifies a signed PDF document at the CMS level: the embedded signature
 * dictionary's {@code /ByteRange} content must hash to the {@code message-digest}
 * signed attribute and the RSA signature must validate against the signer
 * certificate carried in the CMS structure.
 */
public interface PdfVerificationService {

    SignatureVerificationResult verifyPdf(Path signedPdf) throws Exception;
}