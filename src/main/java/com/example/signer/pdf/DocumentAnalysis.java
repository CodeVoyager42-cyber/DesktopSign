package com.example.signer.pdf;

import java.util.List;

/**
 * Pre-sign document analysis (§45): everything the signing workflow needs to
 * know about a document before signing starts — file facts, deep metadata,
 * page/size information and the existing signature situation.
 *
 * @param file                   file-level info (name, size, modified)
 * @param metadata               deep PDF metadata (may be mostly {@code null})
 * @param pdfVersion             PDF version, e.g. {@code "1.7"}
 * @param pageCount              number of pages
 * @param existingSignatures     signatures already present, oldest first
 * @param signatureFieldNames    names of all form signature fields
 * @param emptySignatureFields   how many signature fields exist but are not
 *                               signed yet
 */
public record DocumentAnalysis(
        PdfDocumentInfo file,
        DocumentMetadata metadata,
        String pdfVersion,
        int pageCount,
        List<ExistingSignatureInfo> existingSignatures,
        List<String> signatureFieldNames,
        int emptySignatureFields) {

    /** @return true when the document already carries at least one signature */
    public boolean hasExistingSignatures() {
        return !existingSignatures.isEmpty();
    }

    /** @return true when unsigned signature fields exist in the form */
    public boolean hasEmptySignatureFields() {
        return emptySignatureFields > 0;
    }
}
