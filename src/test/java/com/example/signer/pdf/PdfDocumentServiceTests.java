package com.example.signer.pdf;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PdfDocumentServiceTests {

    @Autowired
    private PdfDocumentService pdfDocumentService;

    @TempDir
    Path tempDir;

    @Test
    void describeExistingFileReturnsSafeMetadata() throws Exception {
        Path pdf = tempDir.resolve("document.pdf");
        byte[] content = "test pdf bytes".getBytes();
        Files.write(pdf, content);

        PdfDocumentInfo info = pdfDocumentService.describe(pdf);

        assertThat(info.path()).isEqualTo(pdf);
        assertThat(info.fileName()).isEqualTo("document.pdf");
        assertThat(info.sizeBytes()).isEqualTo(content.length);
        assertThat(info.lastModified()).isNotNull();
    }

    @Test
    void describeMissingFileThrowsPdfInvalid() {
        Path missing = tempDir.resolve("missing.pdf");

        assertThatThrownBy(() -> pdfDocumentService.describe(missing))
                .isInstanceOfSatisfying(SignerException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PDF_INVALID));
    }

    @Test
    void describeEmptyFileThrowsPdfInvalid() throws Exception {
        Path pdf = tempDir.resolve("empty.pdf");
        Files.createFile(pdf);

        assertThatThrownBy(() -> pdfDocumentService.describe(pdf))
                .isInstanceOfSatisfying(SignerException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PDF_INVALID));
    }

    @Test
    void describeDirectoryThrowsPdfInvalid() {
        assertThatThrownBy(() -> pdfDocumentService.describe(tempDir))
                .isInstanceOfSatisfying(SignerException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PDF_INVALID));
    }

    @Test
    void describeNullThrowsPdfInvalid() {
        assertThatThrownBy(() -> pdfDocumentService.describe(null))
                .isInstanceOfSatisfying(SignerException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PDF_INVALID));
    }
}