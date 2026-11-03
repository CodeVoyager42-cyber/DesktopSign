package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Renders the visible signature stamp (§11/§12): a white box with a gold
 * border showing only true facts — the signer's certificate CN, the actual
 * signing time, and the reason/location only when the user supplied them.
 *
 * <p>All text is baked into the raster, so fonts cannot be missing on the
 * target viewer. Used by the signing service for the real stamp and by the
 * UI for the pre-sign preview, so what the user previews is exactly what is
 * embedded.
 */
public final class SignatureStampRenderer {

    private SignatureStampRenderer() {
    }

    /**
     * Renders the stamp for a placement of {@code widthPts} × {@code heightPts}.
     *
     * @param appearance what to show; {@code null} uses the default appearance
     */
    public static BufferedImage render(float widthPts,
                                       float heightPts,
                                       String signerName,
                                       SigningAlgorithm algorithm,
                                       SignatureAppearance appearance) {
        int wPx = 360;
        int hPx = Math.max(72, Math.round(wPx * heightPts / widthPts));
        BufferedImage image = new BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, wPx, hPx);
            g.setColor(new Color(0xBF, 0x9B, 0x1B));
            int border = Math.max(2, wPx / 80);
            g.fillRect(0, 0, wPx, hPx);
            g.setColor(Color.WHITE);
            g.fillRect(border, border, wPx - 2 * border, hPx - 2 * border);

            List<String> lines = stampLines(signerName, algorithm, appearance);
            int fontSize = Math.max(6, Math.min(wPx / 26, hPx / (lines.size() + 2)));
            g.setColor(new Color(0x2B, 0x2B, 0x2B));
            Font font;
            for (; fontSize > 6; fontSize--) {
                font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
                g.setFont(font);
                int maxWidth = lines.stream()
                        .mapToInt(line -> g.getFontMetrics().stringWidth(line))
                        .max().orElse(0);
                if (maxWidth <= wPx - 4 * border) {
                    break;
                }
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, fontSize)));

            int lineHeight = g.getFontMetrics().getHeight();
            int y = lineHeight + (hPx - lines.size() * lineHeight) / 2;
            for (String line : lines) {
                int textWidth = g.getFontMetrics().stringWidth(line);
                g.drawString(line, (wPx - textWidth) / 2, y);
                y += lineHeight;
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Builds the stamp's text lines. Package-private so tests can assert
     * exactly which facts (display name vs certificate CN) are rendered.
     */
    static List<String> stampLines(String signerName,
                                           SigningAlgorithm algorithm,
                                           SignatureAppearance appearance) {
        String signedAt = DateTimeFormatter
                .ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        boolean showName = appearance == null || appearance.showSignerName();
        boolean showDate = appearance == null || appearance.showDate();
        boolean showCert = appearance != null && appearance.showCertificateLine();
        // The name line shows the signer's own display name when provided;
        // the certificate line always shows the real certificate CN.
        String nameLine = signerName;
        if (appearance != null && appearance.signerDisplayName() != null
                && !appearance.signerDisplayName().isBlank()) {
            nameLine = appearance.signerDisplayName();
        }
        List<String> lines = new ArrayList<>();
        lines.add("DIGITALLY SIGNED BY");
        if (showName) {
            lines.add(nameLine);
        }
        if (showDate) {
            lines.add(signedAt + " UTC");
        }
        if (showCert) {
            lines.add("Certificate: CN=" + signerName);
        }
        if (appearance != null && appearance.reason() != null
                && !appearance.reason().isBlank()) {
            lines.add("Reason: " + appearance.reason().trim());
        }
        if (appearance != null && appearance.location() != null
                && !appearance.location().isBlank()) {
            lines.add("Location: " + appearance.location().trim());
        }
        lines.add(algorithm.getJcaName() + " via PKCS#11");
        return lines;
    }

    /** Small helper used by the renderer's sizing code paths. */
    static int[] fitLines(String[] lines, Graphics2D g, int maxWidth) {
        int widest = Arrays.stream(lines)
                .mapToInt(line -> g.getFontMetrics().stringWidth(line))
                .max().orElse(0);
        return new int[] { widest };
    }
}
