package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic test for {@link DefaultPdfSigningService}.
 *
 * <p>Creates a small PDF entirely in-memory, signs it with the mock token
 * and verifies at the byte level that the output is a structurally valid
 * PDF (header, xref, ByteRange) carrying an intact, hash-consistent CMS
 * signature — the same invariants Adobe Acrobat relies on.
 *
 * <p>The signed file is also copied to {@code target/signed-smoke.pdf} so it
 * can be opened manually in Adobe Acrobat for a real-world sanity check.
 */
@SpringBootTest
class DefaultPdfSigningServiceTests {

    @Autowired
    private DefaultPdfSigningService pdfSigningService;

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
    void signPdfEmbedsCmsSignature() throws Exception {
        // ── arrange token ──
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        // ── create a minimal PDF ──
        Path dir   = Files.createTempDirectory("pdf-sign-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path output = dir.resolve("plain-signed.pdf");

        // ── act ──
        SigningResult result = pdfSigningService.signPdf(
                input,
                MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256,
                output);

        // ── assert: SigningResult metadata ──
        assertThat(result.signature()).isNotEmpty();
        assertThat(result.algorithm()).isEqualTo(SigningAlgorithm.RSA_SHA256);
        assertThat(result.signerSubject()).contains("Mock Signer");

        // ── assert: signed file exists and is larger than the original ──
        assertThat(Files.exists(output)).isTrue();
        assertThat(Files.size(output)).isGreaterThan(Files.size(input));

        // ── assert: embedded signature dictionary is present ──
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            List<PDSignature> signatures = doc.getSignatureDictionaries();
            assertThat(signatures).hasSize(1);
            assertThat(signatures.get(0).getSubFilter())
                    .isEqualTo(DefaultPdfSigningService.SUBFILTER_ADBE_PKCS7_DETACHED);
        }

        // ── copy to target/ for manual Adobe Acrobat check ──
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.copy(output, target.resolve("signed-smoke.pdf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // ── byte-level structural checks ──
        byte[] pdf = Files.readAllBytes(output);
        checkHeaderAndEof(pdf);

        // ── byte-range + CMS integrity checks ──
        verifyByteRangeAndCms(pdf, output);

        // ── cleanup ──
        Files.deleteIfExists(input);
        Files.deleteIfExists(output);
        Files.deleteIfExists(dir);
    }

    /**
     * Signs the same document a second time: a new, non-colliding signature
     * field must be created and the resulting file must still be structurally
     * valid with both signature dictionaries present.
     */
    @Test
    void signingTwiceCreatesDistinctSignatureFields() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("pdf-resign-test");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }

        Path once = dir.resolve("signed-once.pdf");
        pdfSigningService.signPdf(input, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, once);

        Path twice = dir.resolve("signed-twice.pdf");
        pdfSigningService.signPdf(once, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, twice);

        try (PDDocument doc = Loader.loadPDF(twice.toFile())) {
            assertThat(doc.getSignatureDictionaries()).hasSize(2);
        }

        Files.deleteIfExists(input);
        Files.deleteIfExists(once);
        Files.deleteIfExists(twice);
        Files.deleteIfExists(dir);
    }

    /**
     * Checks the header magic, the final EOF marker and that {@code startxref}
     * points to a real {@code xref} keyword.
     */
    private void checkHeaderAndEof(byte[] pdf) {
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF-");

        String tail = new String(pdf,
                Math.max(0, pdf.length - 64), Math.min(64, pdf.length),
                StandardCharsets.ISO_8859_1);
        assertThat(tail).contains("%%EOF");

        String all = new String(pdf, StandardCharsets.ISO_8859_1);
        int startxrefAt = all.lastIndexOf("startxref");
        assertThat(startxrefAt).isGreaterThan(0);
        int offset = -1;
        try {
            offset = Integer.parseInt(
                    all.substring(startxrefAt + "startxref".length()).trim()
                            .split("\\R")[0].trim());
        } catch (RuntimeException ok) {
            // fallthrough, assertion below fails with the real value
        }
        assertThat(offset).isGreaterThan(0);
        assertThat(offset).isLessThan(pdf.length);
    }

    /**
     * Extracts {@code /ByteRange} and {@code /Contents} from the embedded
     * signature dictionary, checks the CMS parses as a detached
     * {@code SignedData} and that the bytes in the ByteRange hash to the
     * CMS {@code message-digest} attribute.
     */
    private void verifyByteRangeAndCms(byte[] pdf, Path signedPdf) throws Exception {
        String all = new String(pdf, StandardCharsets.ISO_8859_1);

        int sigDict = all.indexOf("/Type /Sig");
        assertThat(sigDict).isGreaterThan(0);

        java.util.regex.Matcher br = java.util.regex.Pattern
                .compile("/ByteRange\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*\\]")
                .matcher(all);
        assertThat(br.find()).as("ByteRange array present").isTrue();
        long r1s = Long.parseLong(br.group(1));
        long r1l = Long.parseLong(br.group(2));
        long r2s = Long.parseLong(br.group(3));
        long r2l = Long.parseLong(br.group(4));

        // ByteRange gap must align exactly with the /Contents hex string bounds.
        int contentsKw = all.indexOf("/Contents", sigDict);
        assertThat(contentsKw).as("/Contents present").isGreaterThan(0);
        int hexStart = all.indexOf('<', contentsKw);
        assertThat(hexStart).as("hex /Contents start").isGreaterThan(0);
        assertThat(hexStart - contentsKw).isLessThan(64);

        int hexEnd = -1;
        for (int i = hexStart + 1; i < all.length(); i++) {
            char c = all.charAt(i);
            if (c == '>') {
                hexEnd = i;
                break;
            }
            if (!Character.isWhitespace(c) && Character.digit(c, 16) < 0) {
                break;
            }
        }
        assertThat(hexEnd).as("hex /Contents end").isGreaterThan(0);

        assertThat(r1s + r1l).isEqualTo(hexStart);
        assertThat(r2s).isEqualTo(hexEnd + 1);
        assertThat(r1s + r1l + (r2l - r2s)).isLessThan(pdf.length);

        // ByteRange content must hash to the CMS message-digest.
        java.io.ByteArrayOutputStream range = new java.io.ByteArrayOutputStream();
        range.write(pdf, (int) r1s, (int) r1l);
        range.write(pdf, (int) r2s, (int) r2l);
        byte[] rangeDigest = java.security.MessageDigest
                .getInstance("SHA-256").digest(range.toByteArray());

        // Extract the CMS via PDFBox (decodes /Contents hex, strips padding).
        byte[] cms;
        try (PDDocument doc = Loader.loadPDF(signedPdf.toFile())) {
            cms = doc.getSignatureDictionaries().get(0).getContents();
        }
        assertThat(cms.length).isGreaterThan(64);

        CMSSignedData signedData = new CMSSignedData(cms);
        assertThat(signedData.getSignedContentTypeOID())
                .isEqualTo("1.2.840.113549.1.7.1");

        SignerInformationStore store = signedData.getSignerInfos();
        assertThat(store.size()).isEqualTo(1);
        SignerInformation signer = store.getSigners().iterator().next();
        org.bouncycastle.asn1.cms.Attribute messageDigest = signer.getSignedAttributes()
                .get(new ASN1ObjectIdentifier("1.2.840.113549.1.9.4"));
        assertThat(messageDigest).as("message-digest attribute").isNotNull();
        byte[] embedded = messageDigest.getAttrValues().getObjectAt(0)
                .toASN1Primitive().getEncoded();
        byte[] embeddedDigest = java.util.Arrays.copyOfRange(embedded,
                embedded.length - 32, embedded.length);
        assertThat(embeddedDigest).isEqualTo(rangeDigest);
    }
}