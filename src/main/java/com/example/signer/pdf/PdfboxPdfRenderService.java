package com.example.signer.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@link PdfRenderService} backed by PDFBox's {@link PDFRenderer}.
 *
 * <p>The viewport dimensions follow PDFRenderer's behaviour: for rotated pages
 * the crop-box width/height are swapped so coordinates stay consistent with
 * the rendered image.
 */
@Service
public class PdfboxPdfRenderService implements PdfRenderService {

    @Override
    public DocumentRenderer openDocument(Path pdf) throws IOException {
        return new PdfboxDocumentRenderer(Loader.loadPDF(pdf.toFile()));
    }

    @Override
    public int getPageCount(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    @Override
    public RenderedPdfPage renderPage(Path pdf, int pageIndex, float dpi) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                throw new IOException("Page " + (pageIndex + 1) + " does not exist in the PDF (pages: "
                        + doc.getNumberOfPages() + ")");
            }
            PDPage page = doc.getPage(pageIndex);
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            int rotation = page.getRotation() % 360;
            boolean swapped = rotation == 90 || rotation == 270;
            float viewportWidth = swapped ? box.getHeight() : box.getWidth();
            float viewportHeight = swapped ? box.getWidth() : box.getHeight();

            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);

            return new RenderedPdfPage(image, viewportWidth, viewportHeight,
                    pageIndex, doc.getNumberOfPages(), rotation);
        }
    }

    /**
     * Stateful renderer over one open {@link PDDocument}: parse once, render
     * any page at any resolution without re-reading the file (§53).
     */
    static final class PdfboxDocumentRenderer implements DocumentRenderer {

        private final PDDocument doc;
        private final PDFRenderer renderer;

        PdfboxDocumentRenderer(PDDocument doc) {
            this.doc = doc;
            this.renderer = new PDFRenderer(doc);
        }

        @Override
        public int pageCount() {
            return doc.getNumberOfPages();
        }

        @Override
        public int rotationOf(int pageIndex) {
            return doc.getPage(pageIndex).getRotation() % 360;
        }

        @Override
        public RenderedPdfPage renderPage(int pageIndex, float dpi) throws IOException {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                throw new IOException("Page " + (pageIndex + 1)
                        + " does not exist in the PDF (pages: "
                        + doc.getNumberOfPages() + ")");
            }
            PDPage page = doc.getPage(pageIndex);
            PDRectangle box = page.getCropBox() != null
                    ? page.getCropBox() : page.getMediaBox();
            int rotation = page.getRotation() % 360;
            boolean swapped = rotation == 90 || rotation == 270;
            float viewportWidth = swapped ? box.getHeight() : box.getWidth();
            float viewportHeight = swapped ? box.getWidth() : box.getHeight();

            BufferedImage image = renderer.renderImageWithDPI(
                    pageIndex, dpi, ImageType.RGB);
            return new RenderedPdfPage(image, viewportWidth, viewportHeight,
                    pageIndex, doc.getNumberOfPages(), rotation);
        }

        @Override
        public BufferedImage renderThumbnail(int pageIndex, int targetWidthPx)
                throws IOException {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                throw new IOException("Page " + (pageIndex + 1)
                        + " does not exist in the PDF (pages: "
                        + doc.getNumberOfPages() + ")");
            }
            PDPage page = doc.getPage(pageIndex);
            PDRectangle box = page.getCropBox() != null
                    ? page.getCropBox() : page.getMediaBox();
            int rotation = page.getRotation() % 360;
            boolean swapped = rotation == 90 || rotation == 270;
            float viewportWidth = swapped ? box.getHeight() : box.getWidth();
            float dpi = Math.max(8f, 72f * targetWidthPx / Math.max(1f, viewportWidth));
            return renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        }

        @Override
        public void close() {
            try {
                doc.close();
            } catch (IOException e) {
                // closing must never mask the real failure
            }
        }
    }
}