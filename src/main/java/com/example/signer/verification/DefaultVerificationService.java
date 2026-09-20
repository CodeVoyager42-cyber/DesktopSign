package com.example.signer.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.Signature;

/**
 * Verifies raw byte signatures using the JCA verification API. At this stage
 * of the project the verification is only over raw data; CMS/PKCS#7 wrapped
 * verification will be layered on top in Phase 8.
 */
@Service
public class DefaultVerificationService implements VerificationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultVerificationService.class);

    @Override
    public SignatureVerificationResult verify(SignatureVerificationRequest request) {
        try {
            Signature signature = Signature.getInstance(request.algorithm().getJcaName());
            signature.initVerify(request.signerCertificate().getCertificate());
            signature.update(request.data());
            boolean valid = signature.verify(request.signature());
            if (valid) {
                log.info("Signature verified successfully");
                return SignatureVerificationResult.success(
                        request.algorithm(),
                        request.signerCertificate().getSubject());
            } else {
                log.warn("Signature verification failed (mismatch)");
                return SignatureVerificationResult.failure(
                        "Signature does not match the data",
                        request.algorithm(),
                        request.signerCertificate().getSubject());
            }
        } catch (Exception e) {
            log.error("Verification error", e);
            return SignatureVerificationResult.failure(
                    "Verification error: " + e.getMessage(),
                    request.algorithm(),
                    request.signerCertificate().getSubject());
        }
    }
}