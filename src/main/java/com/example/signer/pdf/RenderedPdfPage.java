package com.example.signer.pdf;

import java.awt.image.BufferedImage;

/**
 * A rendered page together with the geometry needed to map screen coordinates
 * back to PDF points.
 *
 * @param image           the rasterized page
 * @param viewportWidthPoints visual width of the page, in PDF points, as seen by
 *                        the renderer (rotated pages have the box dimensions
 *                        swapped accordingly)
 * @param viewportHeightPoints visual height of the page, in PDF points, as seen
 *                        by the renderer
 * @param pageIndex       zero-based index of the rendered page
 * @param pageCount       total pages in the document
 * @param rotation        the page's {@code /Rotate} value (0, 90, 180, 270)
 */
public record RenderedPdfPage(
        BufferedImage image,
        float viewportWidthPoints,
        float viewportHeightPoints,
        int pageIndex,
        int pageCount,
        int rotation) {

    /** Width of the rendered image, in pixels. */
    public int imageWidth() {
        return image.getWidth();
    }

    /** Height of the rendered image, in pixels. */
    public int imageHeight() {
        return image.getHeight();
    }

    /**
     * Rendering scale: rendered pixels per PDF point. Computed from the image
     * pixel size versus the viewport, so it survives box/Rotate edge cases.
     */
    public float pixelsPerPoint() {
        return imageWidth() / viewportWidthPoints;
    }
}