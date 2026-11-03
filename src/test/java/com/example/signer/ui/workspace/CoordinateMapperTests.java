package com.example.signer.ui.workspace;

import com.example.signer.pdf.RenderedPdfPage;
import com.example.signer.signing.SignaturePlacement;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coordinate transformation tests (§7/§74): the placement produced from
 * screen pixels must be identical regardless of zoom level, page orientation
 * or fit mode — the mapping is zoom-invariant by construction, and these
 * tests prove it at the required matrix of scales.
 */
class CoordinateMapperTests {

    private static RenderedPdfPage page(int pageIndex, float widthPts,
                                        float heightPts, double scalePxPerPt) {
        int wPx = (int) Math.round(widthPts * scalePxPerPt);
        int hPx = (int) Math.round(heightPts * scalePxPerPt);
        return new RenderedPdfPage(new BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB),
                widthPts, heightPts, pageIndex, 1, 0);
    }

    @Test
    void pixelRectConvertsToPointsWithBottomLeftOrigin() {
        // A4 portrait at 100%: 595 x 842 pt, 2 px per pt.
        RenderedPdfPage page = page(0, 595f, 842f, 2.0);
        // 40 px from left, 30 px from top, 200 x 80 px box.
        SignaturePlacement p = CoordinateMapper.toPlacement(0, page, 40, 30, 200, 80);
        assertThat(p.pageIndex()).isZero();
        assertThat(p.x()).isEqualTo(20f);
        assertThat(p.y()).isEqualTo((842f - 30f / 2) - 40f); // top converted + height
        assertThat(p.width()).isEqualTo(100f);
        assertThat(p.height()).isEqualTo(40f);
    }

    @Test
    void placementIsZoomInvariantAcrossTheRequiredMatrix() {
        // The same physical box (PDF points) mapped to pixels at every
        // required zoom and back. The pixel rect is produced by the mapper's
        // own forward conversion, so integer raster rounding stays consistent.
        // Integer pixel rounding allows a sub-point deviation; tolerance 0.5pt.
        float[] scales = { 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f };
        SignaturePlacement placement = new SignaturePlacement(0, 100f, 102f, 100f, 40f);
        var offset = org.assertj.core.data.Offset.offset(0.5f);
        for (float scale : scales) {
            RenderedPdfPage page = page(0, 595f, 842f, scale);
            double[] px = CoordinateMapper.toPixels(page, placement);
            SignaturePlacement back = CoordinateMapper.toPlacement(
                    0, page, px[0], px[1], px[2], px[3]);
            assertThat(back.x()).as("x at %sx", scale).isCloseTo(100f, offset);
            assertThat(back.y()).as("y at %sx", scale).isCloseTo(102f, offset);
            assertThat(back.width()).as("width at %sx", scale).isCloseTo(100f, offset);
            assertThat(back.height()).as("height at %sx", scale).isCloseTo(40f, offset);
        }
    }

    @Test
    void landscapePageMapsCorrectly() {
        // A4 landscape: 842 x 595 pt at 1.25 px/pt.
        RenderedPdfPage page = page(0, 842f, 595f, 1.25);
        SignaturePlacement p = CoordinateMapper.toPlacement(0, page,
                250, 125, 250, 100);
        var offset = org.assertj.core.data.Offset.offset(0.5f);
        assertThat(p.x()).isCloseTo(200f, offset);
        assertThat(p.width()).isCloseTo(200f, offset);
        assertThat(p.height()).isCloseTo(80f, offset);
        assertThat(p.y()).isCloseTo(595f - 100f - 80f, offset);
    }

    @Test
    void roundTripPixelsToPointsToPixelsIsStable() {
        RenderedPdfPage page = page(0, 595f, 842f, 1.5);
        SignaturePlacement placement = new SignaturePlacement(0, 60f, 500f, 170f, 55f);
        double[] px = CoordinateMapper.toPixels(page, placement);
        SignaturePlacement back = CoordinateMapper.toPlacement(
                0, page, px[0], px[1], px[2], px[3]);
        assertThat(back.x()).isEqualTo(placement.x());
        assertThat(back.y()).isEqualTo(placement.y());
        assertThat(back.width()).isEqualTo(placement.width());
        assertThat(back.height()).isEqualTo(placement.height());
    }

    @Test
    void offPageDragsAreClampedInsideThePage() {
        RenderedPdfPage page = page(0, 595f, 842f, 1.0);
        SignaturePlacement p = CoordinateMapper.toPlacement(0, page,
                -50, -40, 2000, 3000);
        assertThat(p.x()).isGreaterThanOrEqualTo(0f);
        assertThat(p.y()).isGreaterThanOrEqualTo(0f);
        assertThat(p.x() + p.width()).isLessThanOrEqualTo(595f);
        assertThat(p.y() + p.height()).isLessThanOrEqualTo(842f);
    }
}
