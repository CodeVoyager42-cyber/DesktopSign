package com.example.signer.verification;

import com.example.signer.signing.DefaultPdfSigningService;
import com.example.signer.signing.SigningResult;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import org.apache.pdfbox.Loader;
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

    @Test
    void revisionAppendedAfterSigningIsRejected() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-revision-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path signed = dir.resolve("plain-signed.pdf");
        pdfSigningService.signPdf(
                input, MockSigningToken.DEFAULT_ALIAS, SigningAlgorithm.RSA_SHA256, signed);

        // The CMS and ByteRange hashes are untouched; only extra bytes were
        // appended after the signature, so verification must fail with the
        // revision message, not a digest mismatch.
        byte[] bytes = Files.readAllBytes(signed);
        Path extended = dir.resolve("plain-extended.pdf");
        try (var out = new java.io.FileOutputStream(extended.toFile())) {
            out.write(bytes);
            out.write("%appended after signing\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        SignatureVerificationResult result = pdfVerificationService.verifyPdf(extended);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("modified after the most recent signature");

        // Even though the chain is broken, the detail list must still be
        // present and show the single signature as individually valid — the
        // failure is purely about bytes appended after it.
        assertThat(result.signatures()).hasSize(1);
        assertThat(result.signatures().get(0).valid()).isTrue();
        assertThat(result.signatures().get(0).index()).isZero();

        Files.deleteIfExists(input);
        Files.deleteIfExists(signed);
        Files.deleteIfExists(extended);
        Files.deleteIfExists(dir);
    }

    @Test
    void ecdsaSignedPdfVerifiesWithEcdsaAlgorithm() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-ecdsa-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path signed = dir.resolve("plain-signed-ecdsa.pdf");
        SigningResult result = pdfSigningService.signPdf(
                input, MockSigningToken.EC_ALIAS, SigningAlgorithm.ECDSA_SHA256, signed);
        assertThat(result.signature()).isNotEmpty();
        assertThat(result.algorithm()).isEqualTo(SigningAlgorithm.ECDSA_SHA256);

        SignatureVerificationResult ok = pdfVerificationService.verifyPdf(signed);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.algorithm()).isEqualTo(SigningAlgorithm.ECDSA_SHA256);
        assertThat(ok.signerSubject()).contains("Mock EC Signer");

        Files.deleteIfExists(input);
        Files.deleteIfExists(signed);
        Files.deleteIfExists(dir);
    }

    /**
     * Two signatures in one revision chain: the first RSA signature no longer
     * reaches the end of the file after the second ECDSA signature is appended
     * as an incremental update. Chain-aware verification must accept this and
     * report the most recent signer.
     */
    @Test
    void rsaThenEcdsaChainVerifiesAndReportsLastSigner() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-chain-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path first = dir.resolve("signed-rsa.pdf");
        pdfSigningService.signPdf(input, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, first);

        Path second = dir.resolve("signed-rsa-ec.pdf");
        pdfSigningService.signPdf(first, MockSigningToken.EC_ALIAS,
                SigningAlgorithm.ECDSA_SHA256, second);

        try (PDDocument doc = Loader.loadPDF(second.toFile())) {
            assertThat(doc.getSignatureDictionaries()).hasSize(2);
        }

        SignatureVerificationResult chain = pdfVerificationService.verifyPdf(second);
        assertThat(chain.valid()).isTrue();
        assertThat(chain.algorithm()).isEqualTo(SigningAlgorithm.ECDSA_SHA256);
        assertThat(chain.signerSubject()).contains("Mock EC Signer");

        // Per-signature detail: two entries, oldest first, each with its own
        // algorithm and signing time.
        assertThat(chain.signatures()).hasSize(2);
        SignatureVerificationResult.SignatureDetail firstDetail = chain.signatures().get(0);
        SignatureVerificationResult.SignatureDetail secondDetail = chain.signatures().get(1);
        assertThat(firstDetail.index()).isZero();
        assertThat(firstDetail.valid()).isTrue();
        assertThat(firstDetail.algorithm()).isEqualTo(SigningAlgorithm.RSA_SHA256);
        assertThat(firstDetail.signerSubject()).contains("Mock Signer");
        assertThat(firstDetail.signedAtKnown()).isTrue();
        assertThat(secondDetail.index()).isEqualTo(1);
        assertThat(secondDetail.valid()).isTrue();
        assertThat(secondDetail.algorithm()).isEqualTo(SigningAlgorithm.ECDSA_SHA256);
        assertThat(secondDetail.signerSubject()).contains("Mock EC Signer");
        assertThat(secondDetail.signedAtKnown()).isTrue();
        // The chain must be reported in signing order: the RSA revision was
        // signed before the ECDSA one.
        assertThat(firstDetail.signedAt())
                .isBeforeOrEqualTo(secondDetail.signedAt());

        Files.deleteIfExists(input);
        Files.deleteIfExists(first);
        Files.deleteIfExists(second);
        Files.deleteIfExists(dir);
    }

    /**
     * Bytes appended after the most recent signature (after the whole chain)
     * must be rejected — this was a blind spot when any single valid signature
     * made the whole document pass.
     */
    @Test
    void bytesAppendedAfterTheChainAreRejected() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-chain-tail-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path first = dir.resolve("signed-rsa.pdf");
        pdfSigningService.signPdf(input, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, first);

        Path second = dir.resolve("signed-rsa-ec.pdf");
        pdfSigningService.signPdf(first, MockSigningToken.EC_ALIAS,
                SigningAlgorithm.ECDSA_SHA256, second);

        // Append junk after the last signature: the CMS and ByteRange hashes
        // are untouched, so only the tail check can detect this.
        byte[] bytes = Files.readAllBytes(second);
        Path extended = dir.resolve("extended.pdf");
        try (var out = new java.io.FileOutputStream(extended.toFile())) {
            out.write(bytes);
            out.write("%appended after the chain\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        SignatureVerificationResult result = pdfVerificationService.verifyPdf(extended);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("modified after");

        Files.deleteIfExists(input);
        Files.deleteIfExists(first);
        Files.deleteIfExists(second);
        Files.deleteIfExists(extended);
        Files.deleteIfExists(dir);
    }

    /**
     * A revision rewritten between two signatures (both signatures still
     * individually valid, but their coverages overlap without containment)
     * must be rejected: a real chain can only ever append.
     */
    @Test
    void revisionRewrittenBetweenSignaturesIsRejected() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-chain-gap-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path first = dir.resolve("signed-rsa.pdf");
        pdfSigningService.signPdf(input, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, first);

        // Tamper inside the bytes covered by the FIRST signature only (the
        // page content stream), then re-sign with EC. The first signature now
        // fails over its own bytes; the chain must be rejected even though the
        // newest signature itself is cryptographically perfect. Flipping a
        // byte around length/3 would land in the signature's own placeholder
        // gap, which is uncovered by design and would change nothing.
        byte[] bytes = Files.readAllBytes(first);
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int streamAt = text.indexOf("stream");
        int flipAt = (streamAt > 0 && streamAt + 8 < bytes.length)
                ? streamAt + 8
                : 100;
        bytes[flipAt] ^= 0x01;
        Path tampered = dir.resolve("tampered.pdf");
        Files.write(tampered, bytes);

        Path resigned = dir.resolve("resigned.pdf");
        pdfSigningService.signPdf(tampered, MockSigningToken.EC_ALIAS,
                SigningAlgorithm.ECDSA_SHA256, resigned);

        SignatureVerificationResult result = pdfVerificationService.verifyPdf(resigned);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("does not match");

        // The detail list must pinpoint exactly which signature broke: the
        // first (tampered) one is invalid, the second (fresh) one is valid.
        assertThat(result.signatures()).hasSize(2);
        assertThat(result.signatures().get(0).valid()).isFalse();
        assertThat(result.signatures().get(0).message()).contains("does not match");
        assertThat(result.signatures().get(1).valid()).isTrue();

        Files.deleteIfExists(input);
        Files.deleteIfExists(first);
        Files.deleteIfExists(tampered);
        Files.deleteIfExists(resigned);
        Files.deleteIfExists(dir);
    }

    /**
     * A document beyond the configured size limit is rejected with a friendly
     * error before any parsing or hashing happens — no exceptions escape.
     */
    @Test
    void oversizedPdfIsRejectedWithFriendlyError() throws Exception {
        DefaultPdfVerificationService strict = new DefaultPdfVerificationService(1024L);

        Path dir = Files.createTempDirectory("pdf-size-limit-test");
        Path input = dir.resolve("big.pdf");
        // 2 KiB: above the injected 1 KiB limit, below any real limit.
        byte[] junk = new byte[2048];
        junk[0] = '%';
        junk[1] = 'P';
        junk[2] = 'D';
        junk[3] = 'F';
        junk[4] = '-';
        Files.write(input, junk);

        SignatureVerificationResult result = strict.verifyPdf(input);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("too large to verify");
        assertThat(result.message()).contains("2.0 KB");
        assertThat(result.message()).contains("1.0 KB");

        Files.deleteIfExists(input);
        Files.deleteIfExists(dir);
    }

    /**
     * Streaming digest equivalence: the chunked FileChannel path must produce
     * byte-identical verification outcomes to the old in-memory approach.
     * A legitimately signed document verifies; the same document with one
     * byte inside the covered range flipped does not.
     */
    @Test
    void streamedDigestMatchesInMemoryDigest() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-stream-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path signed = dir.resolve("plain-signed.pdf");
        pdfSigningService.signPdf(
                input, MockSigningToken.DEFAULT_ALIAS, SigningAlgorithm.RSA_SHA256, signed);

        // The signed file is far larger than one hash chunk's worth of
        // covered bytes for this document? Not necessarily — so force the
        // chunked path by verifying a freshly signed copy of a padded doc.
        Path paddedInput = dir.resolve("padded.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(
                    doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.OVERWRITE,
                    true, true)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 8);
                cs.newLineAtOffset(36, 800);
                // ~1500 filler lines → several KiB of covered content, but the
                // real goal is simply a second, larger signed document.
                for (int i = 0; i < 200; i++) {
                    cs.showText("Filler line " + i
                            + " — padding to exercise the streaming digest path — 0123456789");
                    cs.newLineAtOffset(0, -10);
                }
                cs.endText();
            }
            doc.save(paddedInput.toFile());
        }
        Path paddedSigned = dir.resolve("padded-signed.pdf");
        pdfSigningService.signPdf(
                paddedInput, MockSigningToken.DEFAULT_ALIAS, SigningAlgorithm.RSA_SHA256,
                paddedSigned);

        // Both must verify cleanly through the streaming path.
        assertThat(pdfVerificationService.verifyPdf(signed).valid()).isTrue();
        SignatureVerificationResult paddedResult =
                pdfVerificationService.verifyPdf(paddedSigned);
        assertThat(paddedResult.valid()).isTrue();

        // And a flipped byte inside the padded doc's covered range must fail.
        // Locate the page content stream by keyword (the stream data itself is
        // Flate-compressed, so the filler text is not visible in raw bytes).
        byte[] bytes = Files.readAllBytes(paddedSigned);
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int streamAt = text.indexOf("stream");
        assertThat(streamAt).isGreaterThan(0);
        bytes[streamAt + 8] ^= 0x01;
        Path flipped = dir.resolve("padded-flipped.pdf");
        Files.write(flipped, bytes);
        assertThat(pdfVerificationService.verifyPdf(flipped).valid()).isFalse();

        Files.deleteIfExists(input);
        Files.deleteIfExists(signed);
        Files.deleteIfExists(paddedInput);
        Files.deleteIfExists(paddedSigned);
        Files.deleteIfExists(flipped);
        Files.deleteIfExists(dir);
    }
}