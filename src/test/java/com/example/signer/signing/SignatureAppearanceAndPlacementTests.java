package com.example.signer.signing;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature appearance / stamp renderer (§11/§12): aspect ratio, appearance
 * variants and overlap semantics of placements (§30).
 */
class SignatureAppearanceAndPlacementTests {

    @Test
    void stampMatchesPlacementAspectRatio() {
        BufferedImage stamp = SignatureStampRenderer.render(
                170f, 55f, "Sara Amrani", com.example.signer.token.SigningAlgorithm.RSA_SHA256,
                SignatureAppearance.standard());
        // 360 px wide reference raster; height follows the point aspect ratio.
        assertThat(stamp.getWidth()).isEqualTo(360);
        assertThat(stamp.getHeight()).isGreaterThanOrEqualTo(72);
        float pointRatio = 170f / 55f;
        float pixelRatio = (float) stamp.getWidth() / stamp.getHeight();
        assertThat(pixelRatio).isCloseTo(pointRatio, within(0.15f));
    }

    @Test
    void compactStampIsNotNullAndReasonable() {
        BufferedImage stamp = SignatureStampRenderer.render(
                120f, 40f, "Mock Signer", com.example.signer.token.SigningAlgorithm.RSA_SHA256,
                new SignatureAppearance("Contract approval", "Rabat, Morocco",
                        true, true, false, null));
        assertThat(stamp.getWidth()).isGreaterThan(0);
        assertThat(stamp.getHeight()).isGreaterThan(0);
    }

    @Test
    void stampShowsTypedNameInsteadOfCertificateCn() {
        var algorithm = com.example.signer.token.SigningAlgorithm.RSA_SHA256;
        var withName = new SignatureAppearance(null, null, true, true, true,
                "Sara Amrani");
        var lines = SignatureStampRenderer.stampLines(
                "Mock Signer", algorithm, withName);
        org.assertj.core.api.Assertions.assertThat(lines)
                .contains("DIGITALLY SIGNED BY", "Sara Amrani")
                .contains("Certificate: CN=Mock Signer")
                .doesNotContain("Mock Signer\u0000");
        // The bare CN must not be the name line any more.
        org.assertj.core.api.Assertions.assertThat(lines.indexOf("Mock Signer"))
                .isEqualTo(-1);
    }

    @Test
    void stampFallsBackToCertificateCnWhenNoDisplayName() {
        var algorithm = com.example.signer.token.SigningAlgorithm.RSA_SHA256;
        var withoutName = new SignatureAppearance(null, null, true, true, true,
                null);
        var lines = SignatureStampRenderer.stampLines(
                "Mock Signer", algorithm, withoutName);
        org.assertj.core.api.Assertions.assertThat(lines)
                .contains("DIGITALLY SIGNED BY", "Mock Signer");
    }

    private static org.assertj.core.data.Offset<Float> within(float v) {
        return org.assertj.core.data.Offset.offset(v);
    }

    @Test
    void overlappingPlacementsOnSamePageAreDetected() {
        SignaturePlacement a = new SignaturePlacement(0, 0, 0, 100, 50);
        SignaturePlacement touching = new SignaturePlacement(0, 100, 0, 100, 50);
        SignaturePlacement overlapping = new SignaturePlacement(0, 50, 25, 100, 50);
        SignaturePlacement otherPage = new SignaturePlacement(1, 0, 0, 100, 50);

        assertThat(a.overlaps(a)).isTrue();
        assertThat(a.overlaps(touching)).isFalse();   // edge contact is allowed
        assertThat(a.overlaps(overlapping)).isTrue();
        assertThat(a.overlaps(otherPage)).isFalse();
    }

    @Test
    void appearanceVariantsDifferOnlyInVisibleLines() {
        SignatureAppearance standard = SignatureAppearance.standard();
        SignatureAppearance compact = SignatureAppearance.compact();
        assertThat(standard.showCertificateLine()).isTrue();
        assertThat(compact.showCertificateLine()).isFalse();
        assertThat(compact.showSignerName()).isTrue();
        assertThat(compact.showDate()).isTrue();
    }
}
