package com.example.signer.pdf;

import java.util.Locale;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * PDF document service backed by Apache PDFBox: file-level validation plus
 * deep pre-sign analysis (metadata, page count, PDF version, existing
 * signatures and signature fields).
 */
@Service
public class SimplePdfDocumentService implements PdfDocumentService {

    private static final Logger log = LoggerFactory.getLogger(SimplePdfDocumentService.class);

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

    @Override
    public DocumentAnalysis analyze(Path source) {
        PdfDocumentInfo info = describe(source);
        try (PDDocument doc = Loader.loadPDF(source.toFile())) {
            var dict = doc.getDocumentInformation();
            DocumentMetadata metadata = new DocumentMetadata(
                    blankToNull(dict.getTitle()),
                    blankToNull(dict.getAuthor()),
                    blankToNull(dict.getSubject()),
                    blankToNull(dict.getKeywords()),
                    blankToNull(dict.getCreator()),
                    blankToNull(dict.getProducer()),
                    toInstant(dict.getCreationDate()),
                    toInstant(dict.getModificationDate()));

            int pageCount = doc.getNumberOfPages();
            String pdfVersion = String.format(Locale.ROOT, "%.1f",
                    doc.getVersion());

            List<ExistingSignatureInfo> existing = new ArrayList<>();
            try {
                List<PDSignature>
                        signatures = doc.getSignatureDictionaries();
                for (int i = 0; i < signatures.size(); i++) {
                    var sig = signatures.get(i);
                    existing.add(new ExistingSignatureInfo(
                            i, blankToNull(sig.getName()),
                            toInstant(sig.getSignDate())));
                }
            } catch (Exception e) {
                // A malformed signature dictionary must never block analysis.
                log.debug("Could not enumerate signature dictionaries of {}: {}",
                        source.getFileName(), e.getMessage());
            }

            List<String> fieldNames = new ArrayList<>();
            int emptyFields = 0;
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null) {
                for (var field : acroForm.getFields()) {
                    if (field instanceof PDSignatureField signatureField) {
                        fieldNames.add(field.getPartialName());
                        if (field.getValueAsString() == null
                                || field.getValueAsString().isBlank()) {
                            emptyFields++;
                        }
                    }
                }
            }

            return new DocumentAnalysis(info, metadata, pdfVersion,
                    doc.getNumberOfPages(), List.copyOf(existing),
                    List.copyOf(fieldNames), emptyFields);
        } catch (SignerException e) {
            throw e;
        } catch (Exception e) {
            throw new SignerException(ErrorCode.PDF_INVALID,
                    "The file could not be read as a PDF document: "
                            + source.getFileName(), e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant toInstant(Calendar date) {
        return date == null ? null : date.toInstant();
    }
}