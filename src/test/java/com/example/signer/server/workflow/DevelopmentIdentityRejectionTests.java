package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.BindCertificateRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiErrorType;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.token.DevelopmentSigningIdentities;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.1 server side: with {@code accept-development-identities=false} — the
 * production posture — the coordination server refuses certificates and
 * signatures from the development mock token. (The mock token itself only
 * exists in the {@code dev} profile, so the test keeps {@code dev} for the
 * signing side and switches only the server rule to production.)
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "signature.remote-server.accept-development-identities=false")
class DevelopmentIdentityRejectionTests {

    @Autowired
    private RemoteWorkflowService service;

    @Autowired
    private WorkflowFileStore store;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    private static final String LEGACY =
            DevelopmentSigningIdentities.LEGACY_SHARED_FINGERPRINTS.iterator().next();

    @Test
    void theOldPubliclyKnownMockKeysCanNeverBeAssignedToASignerSlot() {
        assertThatThrownBy(() -> service.createWorkflow(new CreateWorkflowRequest(
                "contract.pdf", "SEQUENTIAL", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", LEGACY))),
                pdf(), "alice"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.code()).isEqualTo(ApiErrorCode.DEVELOPMENT_IDENTITY_REJECTED);
                    assertThat(new ApiErrorType.Handler().handle(api).getStatusCode().value())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
                });

        String id = service.createWorkflow(new CreateWorkflowRequest("contract.pdf",
                "SEQUENTIAL", List.of(new CreateWorkflowRequest.SignerSpec(1, "alice", null))),
                pdf(), "alice").workflowId();
        assertThatThrownBy(() -> service.bindCertificate(id, "alice",
                new BindCertificateRequest(LEGACY)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.DEVELOPMENT_IDENTITY_REJECTED);
        assertThat(store.load(id).signers().get(0).isBound()).isFalse();
    }

    @Test
    void aSignatureMadeWithTheMockTokenIsRejectedAndChangesNothing() throws Exception {
        String fp = mockFingerprint(MockSigningToken.DEFAULT_ALIAS);
        String id = service.createWorkflow(new CreateWorkflowRequest("contract.pdf",
                "SEQUENTIAL", List.of(new CreateWorkflowRequest.SignerSpec(1, "alice", fp))),
                pdf(), "alice").workflowId();

        Path dir = Files.createTempDirectory("dev-identity");
        Path downloaded = dir.resolve("rev0.pdf");
        Files.write(downloaded, service.getDocument(id, "alice"));
        connectToken();
        Path signed = dir.resolve("rev1.pdf");
        workflowSigningService.signNextStep(downloaded, null, signed, 1);

        assertThatThrownBy(() -> service.submitSignature(id, "alice",
                Files.readAllBytes(signed), new SignatureSubmissionRequest(0, null, "op-mock")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.DEVELOPMENT_IDENTITY_REJECTED);

        SigningWorkflowAggregate wf = store.load(id);
        assertThat(wf.status()).isEqualTo(WorkflowState.WAITING_FOR_SIGNER);
        assertThat(wf.revisions()).hasSize(1);
        assertThat(wf.signers().get(0).signed()).isFalse();
        assertThat(store.existsRevision(id, 1)).isFalse();
    }

    private String mockFingerprint(String alias) {
        connectToken();
        String fp = tokenService.getCertificates().stream()
                .filter(c -> alias.equals(c.getAlias()))
                .map(SigningCertificate::getSha256Fingerprint)
                .findFirst().orElseThrow();
        tokenService.disconnect();
        simulator.removeToken();
        return fp;
    }

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    private static byte[] pdf() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
