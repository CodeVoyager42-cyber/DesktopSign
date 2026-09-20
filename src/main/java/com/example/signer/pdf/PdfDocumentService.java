package com.example.signer.pdf;

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
     * @throws com.example.signer.exception.SignerException with
     *         {@code PDF_INVALID} when the source cannot be used
     */
    PdfDocumentInfo describe(Path source);
}