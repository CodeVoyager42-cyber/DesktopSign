package com.example.signer.pdf;

import com.example.signer.exception.SignerException;
import java.nio.file.Path;

/**
 * PDF document services boundary.
 *
 * <p>Later phases implement loading, signing and saving of PDF documents on
 * top of Apache PDFBox. The signing logic never overwrites the source file by
 * default; a signed copy is produced instead.
 */
public interface PdfDocumentService {

    /**
     * Validates that the given path points to a readable, non-empty file and
     * returns safe metadata about it.
     *
     * @throws SignerException with
     *         {@code PDF_INVALID} when the source cannot be used
     */
    PdfDocumentInfo describe(Path source);

    /**
     * Performs the full pre-sign analysis (§45): file facts plus deep PDFBox
     * metadata, page count, PDF version and the existing-signature situation.
     *
     * @throws SignerException with
     *         {@code PDF_INVALID} when the file is missing or unreadable as
     *         a PDF
     */
    DocumentAnalysis analyze(Path source);
}
