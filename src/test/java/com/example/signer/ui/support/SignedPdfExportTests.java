package com.example.signer.ui.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignedPdfExportTests {

    @Test
    void suggestsTheDocumentNameWithASignedSuffix() {
        assertThat(SignedPdfExport.suggestedName("Cv.pdf")).isEqualTo("Cv-signed.pdf");
        assertThat(SignedPdfExport.suggestedName("Contract")).isEqualTo("Contract-signed.pdf");
        assertThat(SignedPdfExport.suggestedName("Report.PDF")).isEqualTo("Report-signed.pdf");
    }

    @Test
    void replacesCharactersWindowsForbidsAndHandlesMissingNames() {
        assertThat(SignedPdfExport.suggestedName("Q1: plan/v2?.pdf"))
                .isEqualTo("Q1_ plan_v2_-signed.pdf");
        assertThat(SignedPdfExport.suggestedName(null)).isEqualTo("document-signed.pdf");
        assertThat(SignedPdfExport.suggestedName("  ")).isEqualTo("document-signed.pdf");
    }
}
