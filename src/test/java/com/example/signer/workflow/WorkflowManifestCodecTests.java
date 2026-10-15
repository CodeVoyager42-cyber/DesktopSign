package com.example.signer.workflow;

import com.example.signer.exception.SignerException;
import com.example.signer.signing.SignaturePlacement;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the portable manifest codec: round-trips, hardened parsing
 * of untrusted bytes, schema versioning and domain validation.
 */
class WorkflowManifestCodecTests {

    private WorkflowManifest sampleManifest() {
        CertificateIdentity alice = new CertificateIdentity(
                "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                "CN=Alice", "CN=Dev CA", "01");
        CertificateIdentity karim = new CertificateIdentity(
                "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00",
                "CN=Karim", "CN=Dev CA", "02");
        return new WorkflowManifest(WorkflowManifest.CURRENT, "wf-123",
                Instant.parse("2026-09-23T10:00:00Z"), Instant.parse("2026-09-23T10:00:00Z"),
                List.of(
                        WorkflowSignerStep.of(1, "Alice", alice, new SignaturePlacement(0, 100, 650, 180, 60)),
                        WorkflowSignerStep.of(2, "Karim", karim, new SignaturePlacement(1, 100, 650, 180, 60))));
    }

    @Test
    void roundTripPreservesAllFields() {
        WorkflowManifest original = sampleManifest();
        byte[] bytes = WorkflowManifestCodec.serialize(original);
        WorkflowManifest parsed = WorkflowManifestCodec.deserialize(bytes);

        assertThat(parsed.version()).isEqualTo(original.version());
        assertThat(parsed.workflowId()).isEqualTo("wf-123");
        assertThat(parsed.createdAt()).isEqualTo(original.createdAt());
        assertThat(parsed.stepsInOrder()).hasSize(2);
        assertThat(parsed.stepsInOrder().get(0).displayName()).isEqualTo("Alice");
        assertThat(parsed.stepsInOrder().get(0).placement().pageIndex()).isZero();
        assertThat(parsed.stepsInOrder().get(0).placement().x()).isEqualTo(100.0f);
        assertThat(parsed.stepsInOrder().get(1).certificate().fingerprint())
                .startsWith("11:22:33");
        assertThat(parsed.nextPendingStep().displayName()).isEqualTo("Alice");
    }

    @Test
    void rejectsNullDocument() {
        byte[] fourNullBytes = {'n', 'u', 'l', 'l'};
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(fourNullBytes))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(com.example.signer.exception.ErrorCode.WORKFLOW_INVALID);
    }

    @Test
    void rejectsNonObjectJson() {
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize("[1,2,3]".getBytes()))
                .isInstanceOf(SignerException.class);
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize("\"hello\"".getBytes()))
                .isInstanceOf(SignerException.class);
    }

    @Test
    void rejectsTruncatedJson() {
        byte[] bytes = WorkflowManifestCodec.serialize(sampleManifest());
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(
                java.util.Arrays.copyOf(bytes, bytes.length / 2)))
                .isInstanceOf(SignerException.class);
    }

    @Test
    void rejectsOversizedInput() {
        byte[] big = new byte[WorkflowManifestCodec.MAX_MANIFEST_BYTES + 1];
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(big))
                .isInstanceOf(SignerException.class);
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(new byte[0]))
                .isInstanceOf(SignerException.class);
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(null))
                .isInstanceOf(SignerException.class);
    }

    @Test
    void futureVersionIsUnsupportedButIdentified() {
        String futureJson = """
                {"workflowManifestVersion":99,"workflowId":"x",
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                 "signers":[]}
                """;
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(futureJson.getBytes()))
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("newer version")
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(com.example.signer.exception.ErrorCode.WORKFLOW_UNSUPPORTED_VERSION);
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        String json = """
                {"workflowManifestVersion":1,"workflowId":"wf-9",
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                 "brandNewField":{"nested":true},
                 "signers":[{"order":1,"displayName":"Alice",
                   "certificate":{"sha256Fingerprint":"AA","subject":"CN=Alice"},
                   "futureStepField":42}]}
                """;
        WorkflowManifest parsed = WorkflowManifestCodec.deserialize(json.getBytes());
        assertThat(parsed.workflowId()).isEqualTo("wf-9");
        assertThat(parsed.stepsInOrder().get(0).displayName()).isEqualTo("Alice");
    }

    @Test
    void domainValidationRunsAfterBinding() {
        // order gap: 1, 3 — invalid
        String json = """
                {"workflowManifestVersion":1,"workflowId":"wf-9",
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                 "signers":[
                   {"order":1,"displayName":"Alice",
                    "certificate":{"sha256Fingerprint":"AA"}},
                   {"order":3,"displayName":"Karim",
                    "certificate":{"sha256Fingerprint":"BB"}}]}
                """;
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(json.getBytes()))
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("order");
    }

    @Test
    void missingSignerCertificateIsRejected() {
        String json = """
                {"workflowManifestVersion":1,"workflowId":"wf-9",
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                 "signers":[{"order":1,"displayName":"Alice"}]}
                """;
        assertThatThrownBy(() -> WorkflowManifestCodec.deserialize(json.getBytes()))
                .isInstanceOf(SignerException.class);
    }

    @Test
    void blankFingerprintIsRejected() {
        assertThatThrownBy(() -> new CertificateIdentity("  ", "CN=x", null, null))
                .isInstanceOf(Exception.class);
        // null is now legal: an UNBOUND identity for signers who bind their
        // own certificate later (§17 enrollment lifecycle).
        CertificateIdentity unbound = new CertificateIdentity(null, "CN=x", null, null);
        assertThat(unbound.isBound()).isFalse();
        assertThat(unbound.matchesFingerprint("AB:CD:EF")).isFalse();
    }

    @Test
    void fingerprintMatchingIsCaseInsensitiveAndFormatNormalized() {
        CertificateIdentity identity = new CertificateIdentity("AB:CD:EF", null, null, null);
        assertThat(identity.matchesFingerprint("ab:cd:ef")).isTrue();
        assertThat(identity.matchesFingerprint(" AB:CD:EF ")).isTrue();
        assertThat(identity.matchesFingerprint("abcdef")).isTrue();
        assertThat(identity.matchesFingerprint("AB:CD:EE")).isFalse();
        assertThat(identity.matchesFingerprint(null)).isFalse();
    }

    @Test
    void tryParseNeverThrows() {
        var nothing = WorkflowManifestCodec.tryParse(null);
        assertThat(nothing).isNull();

        var broken = WorkflowManifestCodec.tryParse("{nope".getBytes());
        assertThat(broken).isNotNull();
        assertThat(broken.ok()).isFalse();
        assertThat(broken.failure()).isNotNull();

        var good = WorkflowManifestCodec.tryParse(
                WorkflowManifestCodec.serialize(sampleManifest()));
        assertThat(good.ok()).isTrue();
        assertThat(good.manifest().workflowId()).isEqualTo("wf-123");
    }
}
