package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.DefaultPdfVerificationService;
import com.example.signer.verification.SignatureVerificationResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Hermetic test for visible (placed) signatures produced by
 * {@link DefaultPdfSigningService}: an A4 page signed at a known rectangle
 * must carry a widget annotation whose {@code /Rect} and {@code /AP}
 * appearance match the requested placement, and the embedded CMS signature
 * must still verify.
 */
@SpringBootTest
class DefaultPdfSigningServiceVisibleSignatureTests {

    @Autowired
    private DefaultPdfSigningService pdfSigningService;

    @Autowired
    private DefaultPdfVerificationService pdfVerificationService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    @Test
    void visibleSignatureIsEmbeddedAtPlacement() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-visible-test");
        Path input = dir.resolve("a4.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(595f, 842f)));
            doc.save(input.toFile());
        }

        // Bottom-left origin: 200x80 box at (60, 700).
        SignaturePlacement placement = new SignaturePlacement(0, 60f, 700f, 200f, 80f);

        Path output = dir.resolve("a4-signed.pdf");
        SigningResult result = pdfSigningService.signPdf(
                input, MockSigningToken.DEFAULT_ALIAS, SigningAlgorithm.RSA_SHA256, output, placement);
        assertThat(result.signature()).isNotEmpty();
        assertThat(Files.size(output)).isGreaterThan(Files.size(input));

        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getSignatureDictionaries()).hasSize(1);

            PDPage page = doc.getPage(0);
            List<PDAnnotation> annots = page.getAnnotations();
            PDAnnotationWidget widget = (PDAnnotationWidget) annots.stream()
                    .filter(a -> a instanceof PDAnnotationWidget)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a widget annotation on the page"));

            PDRectangle rect = widget.getRectangle();
            assertThat(rect.getLowerLeftX()).isCloseTo(60f, within(1f));
            assertThat(rect.getLowerLeftY()).isCloseTo(700f, within(1f));
            assertThat(rect.getWidth()).isCloseTo(200f, within(1f));
            assertThat(rect.getHeight()).isCloseTo(80f, within(1f));

            PDAppearanceDictionary appearance = widget.getAppearance();
            assertThat(appearance).as("widget /AP dictionary").isNotNull();
            assertThat(appearance.getNormalAppearance()).as("widget /N entry").isNotNull();
            assertThat(appearance.getNormalAppearance().getAppearanceStream())
                    .as("widget normal appearance stream").isNotNull();
        }

        SignatureVerificationResult verification = pdfVerificationService.verifyPdf(output);
        assertThat(verification.valid()).isTrue();
        assertThat(verification.signerSubject()).contains("Mock Signer");

        Files.deleteIfExists(input);
        Files.deleteIfExists(output);
        Files.deleteIfExists(dir);
    }

    @Test
    void visibleSignatureLandsWhereDrawnOnCroppedScannedPage() throws Exception {
        // Scanned documents typically have a crop box offset inside a larger
        // media box. The user draws on the VISIBLE (crop) area, so the widget
        // must land at the same spot relative to the crop box — historically
        // it was displaced by exactly the crop offset.
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-cropped-test");
        Path input = dir.resolve("cropped.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setMediaBox(new PDRectangle(0f, 0f, 612f, 1008f));
            page.setCropBox(new PDRectangle(50f, 100f, 512f, 808f));
            doc.addPage(page);
            doc.save(input.toFile());
        }

        // User-visible coordinates (bottom-left origin of the crop area):
        // a 150x60 box at (100, 600).
        SignaturePlacement placement = new SignaturePlacement(0, 100f, 600f, 150f, 60f);

        Path output = dir.resolve("cropped-signed.pdf");
        pdfSigningService.signPdf(
                input, MockSigningToken.DEFAULT_ALIAS, SigningAlgorithm.RSA_SHA256, output, placement);

        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            PDPage page = doc.getPage(0);
            PDAnnotationWidget widget = (PDAnnotationWidget) page.getAnnotations().stream()
                    .filter(a -> a instanceof PDAnnotationWidget)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a widget annotation"));

            // Widget rects live in default user space, so the expected absolute
            // position is the crop offset plus the user-visible placement.
            PDRectangle rect = widget.getRectangle();
            assertThat(rect.getLowerLeftX()).isCloseTo(150f, within(1.5f));
            assertThat(rect.getLowerLeftY()).isCloseTo(700f, within(1.5f));
            assertThat(rect.getWidth()).isCloseTo(150f, within(1.5f));
            assertThat(rect.getHeight()).isCloseTo(60f, within(1.5f));
        }

        Files.deleteIfExists(input);
        Files.deleteIfExists(output);
        Files.deleteIfExists(dir);
    }
}