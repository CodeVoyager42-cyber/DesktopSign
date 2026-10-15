package com.example.signer.remote;

import com.example.signer.signing.SignaturePlacement;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The plain-language signature positions replace typed coordinates. */
class SignaturePositionTests {

    @Test
    void positionsStayInsideTheRightPageAndNeverOverlapBetweenSigners() throws Exception {
        Path pdf = Files.createTempFile("three-pages", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            doc.save(pdf.toFile());
        }
        float w = PDRectangle.A4.getWidth();
        float h = PDRectangle.A4.getHeight();

        for (SignaturePosition position : SignaturePosition.values()) {
            if (position == SignaturePosition.INVISIBLE) {
                assertThat(position.resolve(pdf, 1)).isNull();
                continue;
            }
            SignaturePlacement first = position.resolve(pdf, 1);
            SignaturePlacement second = position.resolve(pdf, 2);
            SignaturePlacement third = position.resolve(pdf, 3);
            int expectedPage = position == SignaturePosition.TOP_RIGHT_FIRST_PAGE ? 0 : 2;
            for (SignaturePlacement p : new SignaturePlacement[] {first, second, third}) {
                assertThat(p.pageIndex()).isEqualTo(expectedPage);
                assertThat(p.x()).isGreaterThanOrEqualTo(0);
                assertThat(p.y()).isGreaterThanOrEqualTo(0);
                assertThat(p.x() + p.width()).isLessThanOrEqualTo(w);
                assertThat(p.y() + p.height()).isLessThanOrEqualTo(h);
            }
            assertThat(first.overlaps(second)).isFalse();
            assertThat(second.overlaps(third)).isFalse();
            assertThat(first.overlaps(third)).isFalse();
        }
    }

    @Test
    void labelsArePlainLanguage() {
        assertThat(SignaturePosition.BOTTOM_RIGHT_LAST_PAGE.toString())
                .isEqualTo("Bottom right of the last page");
        assertThat(SignaturePosition.INVISIBLE.label()).isEqualTo("No visible signature");
    }
}
