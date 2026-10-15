package com.example.signer.remote;

import com.example.signer.signing.SignaturePlacement;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Plain-language choices for where the visible signature goes in the remote
 * signing step, replacing hand-typed PDF coordinates.
 *
 * <p>The box is resolved against the DOWNLOADED revision (real page count and
 * page size). Each signer slot gets its own row, stacked away from the page
 * edge by signer order, so the signatures of a multi-signer workflow never
 * overlap.
 */
public enum SignaturePosition {

    BOTTOM_RIGHT_LAST_PAGE("Bottom right of the last page"),
    BOTTOM_LEFT_LAST_PAGE("Bottom left of the last page"),
    TOP_RIGHT_FIRST_PAGE("Top right of the first page"),
    INVISIBLE("No visible signature");

    /** Signature box size and spacing, in PDF points (1/72 inch). */
    static final float WIDTH = 170f;
    static final float HEIGHT = 50f;
    static final float MARGIN = 36f;
    static final float GAP = 8f;

    private final String label;

    SignaturePosition(String label) {
        this.label = label;
    }

    /** Text shown in the position picker. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * The placement for {@code pdf} and signer slot {@code signerOrder}
     * (1-based), or {@code null} for an invisible signature.
     */
    public SignaturePlacement resolve(Path pdf, Integer signerOrder) {
        if (this == INVISIBLE) {
            return null;
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                return null;
            }
            int pageIndex = this == TOP_RIGHT_FIRST_PAGE ? 0 : pages - 1;
            PDRectangle box = doc.getPage(pageIndex).getMediaBox();
            return place(pageIndex, box.getWidth(), box.getHeight(),
                    signerOrder == null || signerOrder < 1 ? 1 : signerOrder);
        } catch (IOException e) {
            throw new IllegalStateException("The document could not be read to"
                    + " place the signature", e);
        }
    }

    /** Pure geometry, separated for tests. */
    SignaturePlacement place(int pageIndex, float pageWidth, float pageHeight,
                             int signerOrder) {
        float width = Math.min(WIDTH, pageWidth - 2 * MARGIN);
        int rows = Math.max(1, (int) ((pageHeight - 2 * MARGIN + GAP) / (HEIGHT + GAP)));
        int slot = (signerOrder - 1) % rows;
        float offset = slot * (HEIGHT + GAP);
        float x = this == BOTTOM_LEFT_LAST_PAGE ? MARGIN : pageWidth - MARGIN - width;
        float y = this == TOP_RIGHT_FIRST_PAGE
                ? pageHeight - MARGIN - HEIGHT - offset
                : MARGIN + offset;
        return new SignaturePlacement(pageIndex, x, y, width, HEIGHT);
    }
}
