package com.example.signer.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * A reusable, stateful handle over an open PDF document for efficient
 * rendering: the file is parsed once and pages are rendered on demand at any
 * resolution. Implementations must be closed when done. Not thread-safe —
 * render from one background thread at a time.
 */
public interface DocumentRenderer extends AutoCloseable {

    /** Closes the underlying document; never throws. */
    @Override
    void close();

    /** Total number of pages in the document. */
    int pageCount();

    /** The {@code /Rotate} value (0/90/180/270) of the given page. */
    int rotationOf(int pageIndex);

    /**
     * Renders a page at the given resolution.
     *
     * @param dpi rendering resolution (72 == 1 PDF point per pixel)
     */
    RenderedPdfPage renderPage(int pageIndex, float dpi) throws IOException;

    /**
     * Renders a page scaled to approximately {@code targetWidthPx} pixels
     * wide — the natural primitive for thumbnail strips.
     */
    BufferedImage renderThumbnail(int pageIndex, int targetWidthPx) throws IOException;
}
