package com.example.signer.signing;

/**
 * Where a visible signature shall be drawn on a PDF page.
 *
 * <p>Coordinates are expressed in PDF points (1/72 inch) with the origin at the
 * bottom-left corner of the page, as is conventional in the PDF model.
 *
 * @param pageIndex zero-based page index (the first page is {@code 0})
 * @param x         left edge of the signature box, in points
 * @param y         bottom edge of the signature box, in points
 * @param width     width of the signature box, in points
 * @param height    height of the signature box, in points
 */
public record SignaturePlacement(
        int pageIndex,
        float x,
        float y,
        float width,
        float height) {

    public SignaturePlacement {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
    }
}