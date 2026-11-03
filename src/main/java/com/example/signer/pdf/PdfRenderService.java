package com.example.signer.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders PDF pages to raster images for on-screen display.
 */
public interface PdfRenderService {

    /**
     * Opens a reusable renderer over the document: parse once, render any
     * page at any resolution. Prefer this over repeated {@link #renderPage}
     * calls (viewer with thumbnails, multi-page documents).
     */
    DocumentRenderer openDocument(Path pdf) throws IOException;

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