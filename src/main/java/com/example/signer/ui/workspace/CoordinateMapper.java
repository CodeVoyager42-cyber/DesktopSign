package com.example.signer.ui.workspace;

import com.example.signer.pdf.RenderedPdfPage;
import com.example.signer.signing.SignaturePlacement;

/**
 * The single implementation of the screen→PDF transformation layer (§7).
 *
 * <p>Conventions:</p>
 * <ul>
 *   <li>PDF points: origin at the <em>bottom-left</em> corner of the page,
 *       y grows upwards — what the signing services and
 *       {@link SignaturePlacement} use.</li>
 *   <li>Page-image pixels: origin at the <em>top-left</em> corner of the
 *       rendered image, y grows downwards — what the JavaFX overlay uses.</li>
 * </ul>
 *
 * <p>The mapping depends only on the rendered page geometry (image pixels vs
 * viewport points), never on zoom, scroll or window size: zoom changes how
 * many pixels a page occupies but also changes {@code pixelsPerPoint}
 * proportionally, so the conversion is zoom-invariant by construction (§74).
 * All arithmetic is pure — unit-tested at 50–150% zoom, fit modes, portrait
 * and landscape pages.
 */
public final class CoordinateMapper {

    private CoordinateMapper() {
    }

    /**
     * Converts an overlay rectangle in page-image pixels (top-left origin)
     * into a {@link SignaturePlacement} in PDF points (bottom-left origin).
     * The rectangle is clamped to the page first, so an imprecise drag can
     * never produce an out-of-page placement.
     *
     * @param pageIndex zero-based page index for the placement
     * @param page      the rendered page (geometry source of truth)
     * @param px        left edge of the overlay, in image pixels
     * @param py        top edge of the overlay, in image pixels
     * @param pw        overlay width, in image pixels
     * @param ph        overlay height, in image pixels
     */
    public static SignaturePlacement toPlacement(int pageIndex,
                                                 RenderedPdfPage page,
                                                 double px,
                                                 double py,
                                                 double pw,
                                                 double ph) {
        double scale = page.pixelsPerPoint();
        double[] rect = clampToPage(page, px, py, pw, ph);
        double x = rect[0] / scale;
        double y = (page.imageHeight() - (rect[1] + rect[3])) / scale;
        double width = rect[2] / scale;
        double height = rect[3] / scale;
        return new SignaturePlacement(pageIndex,
                (float) x, (float) y, (float) width, (float) height);
    }

    /**
     * Inverse of {@link #toPlacement}: converts a placement in PDF points
     * into an overlay rectangle in page-image pixels (top-left origin),
     * returned as {@code [x, y, width, height]}.
     */
    public static double[] toPixels(RenderedPdfPage page, SignaturePlacement placement) {
        double scale = page.pixelsPerPoint();
        double x = placement.x() * scale;
        double heightPx = placement.height() * scale;
        double y = page.imageHeight() - (placement.y() + placement.height()) * scale;
        return new double[] { x, y, placement.width() * scale, heightPx };
    }

    /** Clamps an overlay rectangle to the rendered page, as {@code [x,y,w,h]}. */
    public static double[] clampToPage(RenderedPdfPage page,
                                       double px, double py, double pw, double ph) {
        double min = 1.0;
        double x = Math.max(0, Math.min(px, page.imageWidth() - min));
        double y = Math.max(0, Math.min(py, page.imageHeight() - min));
        double w = Math.max(min, Math.min(pw, page.imageWidth() - x));
        double h = Math.max(min, Math.min(ph, page.imageHeight() - y));
        return new double[] { x, y, w, h };
    }
}
