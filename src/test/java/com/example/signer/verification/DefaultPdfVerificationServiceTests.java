package com.example.signer.verification;

import com.example.signer.signing.DefaultPdfSigningService;
import com.example.signer.signing.SigningResult;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic test for {@link DefaultPdfVerificationService}: a signed PDF
 * created by {@link DefaultPdfSigningService} must verify cleanly, and a
 * tampered copy must be reported as invalid.
 */
@SpringBootTest
class DefaultPdfVerificationServiceTests {

    @Autowired
    private DefaultPdfSigningService pdfSigningService;

    @Autowired
    private DefaultPdfVerificationService pdfVerificationService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    @Test
    void signedPdfVerifiesAndTamperedCopyDoesNot() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-verify-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path signed = dir.resolve("plain-signed.pdf");
        SigningResult result = pdfSigningService.signPdf(
                input, MockSigningToken.DEFAULT_ALIAS, SigningAlgorithm.RSA_SHA256, signed);
        assertThat(result.signature()).isNotEmpty();

        SignatureVerificationResult ok = pdfVerificationService.verifyPdf(signed);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.signerSubject()).contains("Mock Signer");

        // ── tamper with the document content and re-verify ──
        Path tampered = dir.resolve("plain-tampered.pdf");
        byte[] bytes = Files.readAllBytes(signed);
        // Flip a byte inside the page content stream (signed region), which
        // keeps the file parseable while invalidating the signature.
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int streamAt = text.indexOf("stream");
        int flipAt = (streamAt > 0 && streamAt + 8 < bytes.length)
                ? streamAt + 8
                : bytes.length / 3;
        bytes[flipAt] ^= 0x01;
        Files.write(tampered, bytes);

        SignatureVerificationResult bad = pdfVerificationService.verifyPdf(tampered);
        assertThat(bad.valid()).isFalse();
    }
}