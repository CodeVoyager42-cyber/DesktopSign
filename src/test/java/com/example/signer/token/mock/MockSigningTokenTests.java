package com.example.signer.token.mock;

import com.example.signer.token.SigningCertificate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock token's dev identities must be stable across runs: fingerprints
 * captured in one session (workflow creation or certificate binding) stay
 * valid after an app restart, mirroring how a real USB token keeps its
 * certificates.
 */
class MockSigningTokenTests {

    @Test
    void certificateFingerprintsAreStableAcrossInstances() {
        MockSigningToken first = new MockSigningToken();
        MockSigningToken second = new MockSigningToken();

        List<String> firstPrints = first.getSigningCertificates().stream()
                .map(SigningCertificate::getSha256Fingerprint)
                .toList();
        List<String> secondPrints = second.getSigningCertificates().stream()
                .map(SigningCertificate::getSha256Fingerprint)
                .toList();

        assertThat(firstPrints).hasSize(3);
        assertThat(firstPrints).isEqualTo(secondPrints);
        // The three identities are distinct certificates.
        assertThat(firstPrints).doesNotHaveDuplicates();
    }

    @Test
    void identitiesCarryTheExpectedSubjects() {
        MockSigningToken token = new MockSigningToken();
        List<String> subjects = token.getSigningCertificates().stream()
                .map(SigningCertificate::getSubject)
                .toList();
        // getSubject() renders RFC2253 (reversed order, no spaces).
        assertThat(subjects.get(0)).contains("CN=Mock Signer");
        assertThat(subjects.get(1)).contains("CN=Mock Third Signer");
        assertThat(subjects.get(2)).contains("CN=Mock EC Signer");
        assertThat(token.getSigningCertificates())
                .extracting(SigningCertificate::getAlias)
                .containsExactly(MockSigningToken.DEFAULT_ALIAS,
                        MockSigningToken.THIRD_ALIAS, MockSigningToken.EC_ALIAS);
    }
}
