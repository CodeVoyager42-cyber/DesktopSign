package com.example.signer.verification;

import com.example.signer.signing.DefaultPdfSigningService;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature-details panel shows only facts read from the signed file:
 * this pins what the verifier extracts from a real signature made with the
 * (development) token, and that nothing is claimed that is not checked.
 */
@SpringBootTest
class SignaturePropertiesTests {

    @Autowired
    private DefaultPdfSigningService pdfSigningService;

    @Autowired
    private DefaultPdfVerificationService verificationService;

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
    void propertiesComeFromTheEmbeddedSignatureAndCertificate() throws Exception {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path dir = Files.createTempDirectory("sig-props");
        Path input = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(input.toFile());
        }
        Path signed = dir.resolve("signed.pdf");
        pdfSigningService.signPdf(input, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, signed);

        SignatureVerificationResult result = verificationService.verifyPdf(signed);
        assertThat(result.valid()).isTrue();
        SignatureProperties p = result.signatures().get(0).properties();
        assertThat(p).isNotNull();

        // The real format is PKCS#7 detached — never labelled PAdES.
        assertThat(p.subFilter()).isEqualTo("adbe.pkcs7.detached");
        assertThat(p.formatLabel()).doesNotContain("PAdES").contains("PKCS#7");
        assertThat(p.digestAlgorithm()).isEqualTo("SHA-256");
        assertThat(p.signatureAlgorithm()).isEqualToIgnoringCase("SHA256withRSA");

        // Certificate facts straight from the X.509 certificate.
        assertThat(p.certSubject()).contains("CN=Mock Signer");
        assertThat(p.certIssuer()).isEqualTo(p.certSubject());
        assertThat(p.certSelfSigned()).isTrue();
        assertThat(p.certSerialHex()).isNotBlank();
        assertThat(p.certNotBefore()).isBefore(Instant.now());
        assertThat(p.certNotAfter()).isAfter(Instant.now());
        assertThat(p.certValidAtSigningTime()).isTrue();

        // Dictionary entries: reason is written by the signer; no location
        // was given, so none is reported.
        assertThat(p.reason()).isNotBlank();
        assertThat(p.location()).isNull();

        // What is NOT checked is said explicitly.
        assertThat(p.trust()).isEqualTo(SignatureProperties.TrustStatus.NOT_VERIFIED);
        assertThat(p.timestamped()).isFalse();
    }

    @Test
    void distinguishedNameHelpers() {
        assertThat(SignatureProperties.commonName("CN=Jane Doe,OU=Legal,O=ACME"))
                .isEqualTo("Jane Doe");
        assertThat(SignatureProperties.email("E=jane@acme.test,CN=Jane"))
                .isEqualTo("jane@acme.test");
        assertThat(SignatureProperties.email("CN=Jane")).isNull();
        assertThat(new SignatureProperties("ETSI.CAdES.detached", null, null, null,
                "SHA-256", "SHA256withRSA", "CN=a", "CN=b", "1", null, null, false,
                null, false, SignatureProperties.TrustStatus.NOT_VERIFIED).formatLabel())
                .startsWith("PAdES");
    }
}
