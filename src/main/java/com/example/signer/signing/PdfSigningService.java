package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;

import java.nio.file.Path;

/**
 * Signs a PDF document by embedding a CMS (PAdES-like) signature inside a
 * signed copy of the file. The source document is never modified.
 */
public interface PdfSigningService {

    /**
     * Creates a signed copy of {@code inputPdf} with an embedded CMS
     * signature produced by the token.
     *
     * @param inputPdf  source PDF (must exist and be readable)
     * @param keyAlias  alias of the private key on the token
     * @param algorithm signing algorithm (e.g. RSA_SHA256)
     * @param outputPdf destination for the signed copy (must not be the same
     *                  file as {@code inputPdf})
     * @return the raw {@link SigningResult} produced by the token (for
     *         downstream verification)
     * @throws Exception if the token is not authenticated, the certificate is
     *                   not found, or PDF processing fails
     */
    SigningResult signPdf(Path inputPdf,
                          String keyAlias,
                          SigningAlgorithm algorithm,
                          Path outputPdf) throws Exception;

    /**
     * Creates a signed copy of {@code inputPdf} like
     * {@link #signPdf(Path, String, SigningAlgorithm, Path)} but additionally
     * draws a visible signature box (the signer certificate data) at the given
     * {@code placement}. Passing {@code null} behaves like the 4-argument
     * variant (invisible signature).
     *
     * @param inputPdf  source PDF (must exist and be readable)
     * @param keyAlias  alias of the private key on the token
     * @param algorithm signing algorithm (e.g. RSA_SHA256)
     * @param outputPdf destination for the signed copy (must not be the same
     *                  file as {@code inputPdf})
     * @param placement where the visible signature must be drawn, or
     *                  {@code null} for an invisible signature
     * @return the raw {@link SigningResult} produced by the token (for
     *         downstream verification)
     * @throws Exception if the token is not authenticated, the certificate is
     *                   not found, or PDF processing fails
     */
    SigningResult signPdf(Path inputPdf,
                          String keyAlias,
                          SigningAlgorithm algorithm,
                          Path outputPdf,
                          SignaturePlacement placement) throws Exception;

    /**
     * As above, with full control over the visible signature appearance and
     * the signature dictionary's {@code /Reason} and {@code /Location}.
     *
     * @param appearance what the visible stamp shows; {@code null} uses the
     *                   application's default appearance
     */
    SigningResult signPdf(Path inputPdf,
                          String keyAlias,
                          SigningAlgorithm algorithm,
                          Path outputPdf,
                          SignaturePlacement placement,
                          SignatureAppearance appearance) throws Exception;

    /**
     * Document-side hook invoked on the in-memory PDF before the signing
     * incremental save: anything it writes to the document (e.g. a workflow
     * manifest via {@code WorkflowManifestStore.writer}) is serialised in the
     * SAME revision as the new signature, so the signature's byte range
     * covers it and previous signatures stay valid.
     */
    interface DocumentCustomizer {
        void apply(org.apache.pdfbox.pdmodel.PDDocument document) throws Exception;
    }

    /** As the 6-arg overload, plus the pre-save document hook above. */
    SigningResult signPdf(Path inputPdf,
                          String keyAlias,
                          SigningAlgorithm algorithm,
                          Path outputPdf,
                          SignaturePlacement placement,
                          SignatureAppearance appearance,
                          DocumentCustomizer documentCustomizer) throws Exception;
}
