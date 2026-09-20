package com.example.signer.pdf;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Basic PDF document service implementation. Full PDFBox-based processing is
 * added in a later phase; today only safe file-level validation is performed.
 */
@Service
public class SimplePdfDocumentService implements PdfDocumentService {

    @Override
    public PdfDocumentInfo describe(Path source) {
        if (source == null) {
            throw new SignerException(ErrorCode.PDF_INVALID, "No PDF source provided");
        }
        if (!Files.exists(source)) {
            throw new SignerException(ErrorCode.PDF_INVALID,
                    "PDF file does not exist: " + source.getFileName());
        }
        if (Files.isDirectory(source)) {
            throw new SignerException(ErrorCode.PDF_INVALID,
                    "Selected path is a directory, not a PDF file");
        }
        if (!Files.isReadable(source)) {
            throw new SignerException(ErrorCode.PDF_INVALID,
                    "PDF file is not readable: " + source.getFileName());
        }
        try {
            long size = Files.size(source);
            if (size <= 0) {
                throw new SignerException(ErrorCode.PDF_INVALID,
                        "PDF file is empty: " + source.getFileName());
            }
            return new PdfDocumentInfo(
                    source,
                    source.getFileName().toString(),
                    size,
                    Files.getLastModifiedTime(source).toInstant());
        } catch (SignerException e) {
            throw e;
        } catch (Exception e) {
            throw new SignerException(ErrorCode.PDF_INVALID,
                    "PDF file could not be inspected: " + source.getFileName(), e);
        }
    }
}