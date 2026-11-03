package com.example.signer.pdf;

import java.time.Instant;

/**
 * Deep document metadata extracted from the PDF trailer dictionary —
 * distinct from {@link PdfDocumentInfo} (file-level facts) and from
 * signature metadata. Only values actually present in the document are
 * populated; missing values are {@code null} and the UI renders them as
 * "Not specified" (never fabricated).
 *
 * @param title    document title ({@code /Title})
 * @param author   author ({@code /Author})
 * @param subject  subject ({@code /Subject})
 * @param keywords keywords ({@code /Keywords})
 * @param creator  creating application ({@code /Creator})
 * @param producer PDF producer ({@code /Producer})
 * @param created  creation date ({@code /CreationDate})
 * @param modified last modification date ({@code /ModDate})
 */
public record DocumentMetadata(
        String title,
        String author,
        String subject,
        String keywords,
        String creator,
        String producer,
        Instant created,
        Instant modified) {

    /** Metadata with every field absent (used when the dictionary is empty). */
    public static DocumentMetadata empty() {
        return new DocumentMetadata(null, null, null, null, null, null, null, null);
    }
}
