package com.example.signer.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Hermetic test for {@link PdfboxPdfRenderService}: known page sizes render at
 * the requested DPI and rotated pages swap the reported viewport dimensions.
 */
@SpringBootTest
class PdfboxPdfRenderServiceTests {

    @Autowired
    private PdfRenderService renderService;

    @Test
    void rendersA4PageAtRequestedDpi() throws Exception {
        Path dir = Files.createTempDirectory("pdf-render-test");
        Path pdf = dir.resolve("a4.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(595f, 842f)));
            doc.save(pdf.toFile());
        }

        assertThat(renderService.getPageCount(pdf)).isEqualTo(1);

        RenderedPdfPage page = renderService.renderPage(pdf, 0, 72f);
        assertThat(page.pageCount()).isEqualTo(1);
        assertThat(page.rotation()).isZero();
        assertThat(page.viewportWidthPoints()).isEqualTo(595f);
        assertThat(page.viewportHeightPoints()).isEqualTo(842f);
        // At 72 DPI one PDF point equals one pixel (renderer may round up).
        assertThat(page.imageWidth()).isCloseTo(595, within(2));
        assertThat(page.imageHeight()).isCloseTo(842, within(2));
        assertThat(page.pixelsPerPoint()).isCloseTo(1f, within(0.02f));

        Files.deleteIfExists(pdf);
        Files.deleteIfExists(dir);
    }

    @Test
    void rotatedPageSwapsViewportDimensions() throws Exception {
        Path dir = Files.createTempDirectory("pdf-render-test");
        Path pdf = dir.resolve("rotated.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595f, 842f));
            page.setRotation(90);
            doc.addPage(page);
            doc.save(pdf.toFile());
        }

        RenderedPdfPage page = renderService.renderPage(pdf, 0, 72f);
        assertThat(page.rotation()).isEqualTo(90);
        assertThat(page.viewportWidthPoints()).isEqualTo(842f);
        assertThat(page.viewportHeightPoints()).isEqualTo(595f);

        Files.deleteIfExists(pdf);
        Files.deleteIfExists(dir);
    }

    @Test
    void pageOutOfBoundsThrows() throws Exception {
        Path dir = Files.createTempDirectory("pdf-render-test");
        Path pdf = dir.resolve("single.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }

        assertThatThrownBy(() -> renderService.renderPage(pdf, 2, 72f))
                .isInstanceOf(IOException.class);

        Files.deleteIfExists(pdf);
        Files.deleteIfExists(dir);
    }
}