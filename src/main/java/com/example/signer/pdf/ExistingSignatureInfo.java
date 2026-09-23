package com.example.signer.pdf;

import java.time.Instant;

/**
 * Brief description of a digital signature already present in a PDF, used by
 * pre-sign analysis and the signatures panel. Cryptographic validity is
 * deliberately <em>not</em> part of this record — it is produced by the
 * verification service, keeping analysis cheap and the two concerns separate.
 *
 * @param index        zero-based position in the revision chain (oldest first)
 * @param signerName   signer name from the signature dictionary ({@code /Name}),
 *                     or {@code null} when absent
 * @param signedAt     signing time from {@code /M}, or {@code null} when absent
 */
public record ExistingSignatureInfo(int index, String signerName, Instant signedAt) {

    public boolean signerNameKnown() {
        return signerName != null && !signerName.isBlank();
    }

    public boolean signedAtKnown() {
        return signedAt != null;
    }
}
