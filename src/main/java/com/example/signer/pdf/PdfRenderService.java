package com.example.signer.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders PDF pages to raster images for on-screen display.
 */
public interface PdfRenderService {

    /**
     * @param pdf the PDF document
     * @return the number of pages in the document
     */
    int getPageCount(Path pdf) throws IOException;

    /**
     * Renders a single page.
     *
     * @param pdf      the PDF document
     * @param pageIndex zero-based page index
     * @param dpi      rendering resolution (72 {@code ==} 1 PDF point per pixel)
     * @return the rendered page plus the page geometry needed for coordinate
     *         mapping
     */
    RenderedPdfPage renderPage(Path pdf, int pageIndex, float dpi) throws IOException;
}