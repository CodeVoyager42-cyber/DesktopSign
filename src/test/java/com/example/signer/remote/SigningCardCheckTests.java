package com.example.signer.remote;

import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockSigningTokenProvider;
import com.example.signer.workflow.CertificateIdentity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SigningCardCheckTests {

    private final List<SigningCertificate> certs = new MockSigningToken().getSigningCertificates();

    private static CertificateIdentity identity(String fingerprint) {
        return new CertificateIdentity(fingerprint, "CN=x", "CN=x", "1");
    }

    @Test
    void matchingCertificateIsReadyAndIsTheRealOne() {
        SigningCertificate second = certs.get(1);
        SigningCardCheck check = SigningCardCheck.evaluate(certs,
                identity(second.getSha256Fingerprint().toLowerCase()));
        assertThat(check.state()).isEqualTo(SigningCardCheck.State.READY);
        assertThat(check.certificate()).isSameAs(second);
    }

    @Test
    void otherCertificatesAreRejectedAndNothingIsInvented() {
        SigningCardCheck check = SigningCardCheck.evaluate(certs, identity("AB".repeat(32)));
        assertThat(check.state()).isEqualTo(SigningCardCheck.State.WRONG_CERTIFICATE);
        assertThat(check.state().canSign()).isFalse();
        assertThat(check.certificate()).isNull();
        assertThat(SigningCardCheck.evaluate(List.of(), identity("AB".repeat(32))).state())
                .isEqualTo(SigningCardCheck.State.NO_CERTIFICATE);
    }

    @Test
    void absentTokenIsReportedAsSmartCardRequired() {
        MockSigningToken token = new MockSigningToken();
        token.setPresent(false);
        TokenService service = new TokenService(new MockSigningTokenProvider(token));
        assertThat(SigningCardCheck.run(service, null).state())
                .isEqualTo(SigningCardCheck.State.NOT_DETECTED);
        token.setPresent(true);
        assertThat(SigningCardCheck.run(service,
                identity(certs.get(0).getSha256Fingerprint())).state())
                .isEqualTo(SigningCardCheck.State.READY);
    }
}
