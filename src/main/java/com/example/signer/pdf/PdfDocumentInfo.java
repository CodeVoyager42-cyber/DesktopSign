package com.example.signer.pdf;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Safe, non-sensitive metadata describing a PDF document on disk.
 */
public record PdfDocumentInfo(
        Path path,
        String fileName,
        long sizeBytes,
        Instant lastModified) {
}