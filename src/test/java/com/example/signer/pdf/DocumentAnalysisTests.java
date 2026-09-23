package com.example.signer.pdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-sign document analysis (§45): metadata extraction, missing metadata
 * (must be absent, never fabricated), page count, PDF version and the
 * existing-signature situation.
 */
class DocumentAnalysisTests {

    @TempDir
    Path dir;

    @Test
    void analyzesMetadataPagesVersionAndExistingSignature() throws Exception {
        Path pdf = dir.resolve("meta.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            var info = doc.getDocumentInformation();
            info.setTitle("Employment Agreement");
            info.setAuthor("Company Name");
            info.setSubject("Employment Contract");
            info.setKeywords("contract, employee");
            info.setCreator("Test Creator");
            info.setProducer("Test Producer");
            PDSignature signature = new PDSignature();
            signature.setName("Ahmed Benali");
            signature.setSignDate(Calendar.getInstance());
            doc.addSignature(signature);
            doc.save(pdf.toFile());
        }

        DocumentAnalysis analysis = new SimplePdfDocumentService().analyze(pdf);
        assertThat(analysis.pageCount()).isEqualTo(1);
        assertThat(analysis.pdfVersion()).isNotBlank();
        assertThat(analysis.metadata().title()).isEqualTo("Employment Agreement");
        assertThat(analysis.metadata().author()).isEqualTo("Company Name");
        assertThat(analysis.metadata().subject()).isEqualTo("Employment Contract");
        assertThat(analysis.metadata().keywords()).isEqualTo("contract, employee");
        assertThat(analysis.metadata().creator()).isEqualTo("Test Creator");
        assertThat(analysis.metadata().producer()).isEqualTo("Test Producer");
        assertThat(analysis.hasExistingSignatures()).isTrue();
        assertThat(analysis.existingSignatures()).hasSize(1);
        assertThat(analysis.existingSignatures().get(0).signerName())
                .isEqualTo("Ahmed Benali");
    }

    @Test
    void missingMetadataIsAbsentRatherThanFabricated() throws Exception {
        Path pdf = dir.resolve("bare.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }

        DocumentAnalysis analysis = new SimplePdfDocumentService().analyze(pdf);
        assertThat(analysis.pageCount()).isEqualTo(1);
        assertThat(analysis.metadata().title()).isNull();
        assertThat(analysis.metadata().author()).isNull();
        assertThat(analysis.metadata().created()).isNull();
        assertThat(analysis.hasExistingSignatures()).isFalse();
        assertThat(analysis.signatureFieldNames()).isEmpty();
    }

    @Test
    void multiPageAnalysisAndRejectsNonPdf() throws Exception {
        Path pdf = dir.resolve("multi.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 12; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(pdf.toFile());
        }

        DocumentAnalysis analysis = new SimplePdfDocumentService().analyze(pdf);
        assertThat(analysis.pageCount()).isEqualTo(12);

        Path notPdf = dir.resolve("not.pdf");
        Files.writeString(notPdf, "this is not a pdf");
        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.signer.exception.SignerException.class,
                () -> new SimplePdfDocumentService().analyze(notPdf));
    }

    @Test
    void existingDocumentRoundTripsThroughLoader() throws Exception {
        Path pdf = dir.resolve("roundtrip.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        try (PDDocument reloaded = Loader.loadPDF(pdf.toFile())) {
            assertThat(reloaded.getNumberOfPages()).isEqualTo(1);
        }
    }
}
