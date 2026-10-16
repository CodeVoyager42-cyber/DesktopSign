package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.client.RemoteApiException;
import com.example.signer.client.RemoteSigningService;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.GuestSessionView;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.SignatureStatus;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guest signing over real HTTP against PostgreSQL: an invited signer opens
 * the emailed link and signs WITHOUT any SignFlow account or password; the
 * guest session reaches only its own signing task, and dies as soon as the
 * invitation is revoked, expires, is replaced or is completed.
 *
 * <p>Invitation authorization (the token) and the digital signature (the
 * certificate on the smart card — here the mock token) stay separate: the
 * signature's identity is asserted from the signed PDF, never from the
 * invitation.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-guest",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}"
        })
@org.springframework.test.context.ActiveProfiles("server")
@Import(InvitationSignerLifecycleTests.CapturedMailbox.class)
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class GuestSigningSessionE2ETests {

    @LocalServerPort
    private int port;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        tokenService.disconnect();
        simulator.removeToken();
        InvitationSignerLifecycleTests.CapturedMailbox.LINKS.clear();
    }

    @Test
    void anInvitedSignerSignsWithoutAnyAccountOrPassword() throws Exception {
        String email = freshEmail();
        Invite invite = invite(email, fingerprint(MockSigningToken.DEFAULT_ALIAS));

        // No username, no password: only the link's token.
        GuestSessionView session = WorkflowApiClient.openGuestSession(base(), invite.token());
        assertThat(session.workflowId()).isEqualTo(invite.workflowId());
        assertThat(session.email()).isEqualTo(email);
        assertThat(session.requestedBy()).isEqualTo("alice");
        assertThat(session.signerOrder()).isEqualTo(1);
        assertThat(session.expiresIn()).isPositive().isLessThanOrEqualTo(3600);
        assertThat(session.accessToken()).doesNotContain(invite.token());

        // Closing the app half-way and clicking the link again works until
        // the document is signed.
        assertThat(WorkflowApiClient.invitationPreview(base(), invite.token()).status())
                .isEqualTo("ACCEPTED");
        session = WorkflowApiClient.openGuestSession(base(), invite.token());

        WorkflowApiClient guest = WorkflowApiClient.forGuest(base(), session);
        try {
            RemoteSigningService signing = new RemoteSigningService(
                    guest, workflowSigningService, tokenService);
            Path dir = Files.createTempDirectory("guest-sign");
            RemoteSigningService.ReviewPackage review = signing.prepareReview(invite.workflowId(), dir);
            assertThat(review.task().signerOrder()).isEqualTo(1);

            simulator.insertToken();
            AtomicReference<Path> signedFile = new AtomicReference<>();
            SignaturePlacement box = new SignaturePlacement(0, 100f, 120f, 170f, 55f);
            SignatureSubmissionResponse response = signing.signRemoteStep(invite.workflowId(),
                    MockSigningToken.defaultPin(), (f, o) -> box, null,
                    review.task().documentRevision(), dir,
                    new RemoteSigningService.Listener() {
                        @Override
                        public void signedFile(Path signedPdf) {
                            signedFile.set(signedPdf);
                        }
                    });
            assertThat(response.completed()).isTrue();

            // The digital signature's identity comes from the certificate
            // in the PDF (the mock smart card), not from the invitation.
            var verification = workflowSigningService.load(signedFile.get()).verification();
            var sig = verification.signatures().get(0);
            assertThat(SignatureStatus.of(sig, verification)).isEqualTo(SignatureStatus.VALID);
            assertThat(sig.properties().certSubject()).contains("CN=Mock Signer")
                    .doesNotContain(email);
        } finally {
            guest.close();
        }

        // Signing did not create or activate an account for the guest.
        UserEntity identity = users.findByEmail(email).orElseThrow();
        assertThat(identity.isActive()).isFalse();
        assertThat(identity.getPasswordHash()).isNull();

        // The link is done: re-opening it says "completed".
        assertThatThrownBy(() -> WorkflowApiClient.openGuestSession(base(), invite.token()))
                .isInstanceOfSatisfying(RemoteApiException.class,
                        e -> assertThat(e.serverCode()).isEqualTo("INVITATION_COMPLETED"));
    }

    @Test
    void aGuestSessionReachesOnlyItsOwnSigningTask() throws Exception {
        String email = freshEmail();
        String fp = fingerprint(MockSigningToken.DEFAULT_ALIAS);
        Invite mine = invite(email, fp);
        // The SAME person is also invited to a second workflow: the first
        // link must still not open it.
        Invite other = invite(email, fp);
        String token = WorkflowApiClient.openGuestSession(base(), mine.token()).accessToken();
        String wf = mine.workflowId();

        assertThat(call("GET", "/api/workflows/" + wf + "/task", token)).isEqualTo(200);
        assertThat(call("GET", "/api/workflows/" + wf + "/document", token)).isEqualTo(200);

        assertThat(call("GET", "/api/workflows/" + other.workflowId() + "/task", token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + other.workflowId() + "/document", token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/signing-tasks", token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/my-tasks", token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/owned", token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + wf, token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + wf + "/audit", token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + wf + "/revisions", token)).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + wf + "/invitations", token)).isEqualTo(403);
        assertThat(call("POST", "/api/workflows/" + wf + "/invitations", token)).isEqualTo(403);
        assertThat(call("POST", "/api/workflows/" + wf + "/cancel", token)).isEqualTo(403);
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/alice/certificate", token))
                .isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + wf + "/eligible-signers", token)).isEqualTo(403);
        // Account endpoints refuse a guest token outright.
        assertThat(call("GET", "/api/auth/me", token)).isEqualTo(401);
        assertThat(workflows.getWorkflow(wf, "alice").status()).isNotEqualTo("CANCELLED");
    }

    @Test
    void revokingTheInvitationEndsALiveGuestSessionAndTheLink() throws Exception {
        Invite invite = invite(freshEmail(), fingerprint(MockSigningToken.DEFAULT_ALIAS));
        String token = WorkflowApiClient.openGuestSession(base(), invite.token()).accessToken();
        assertThat(call("GET", "/api/workflows/" + invite.workflowId() + "/task", token))
                .isEqualTo(200);

        invitations.revoke(latestInvitation(invite.workflowId()).getId(), "alice");

        assertThat(call("GET", "/api/workflows/" + invite.workflowId() + "/task", token))
                .isEqualTo(403);
        assertThatThrownBy(() -> WorkflowApiClient.openGuestSession(base(), invite.token()))
                .isInstanceOfSatisfying(RemoteApiException.class,
                        e -> assertThat(e.serverCode()).isEqualTo("INVITATION_REVOKED"));
    }

    @Test
    void expiredInvalidAndReplacedLinksAreRejected() throws Exception {
        // invalid
        assertThatThrownBy(() -> WorkflowApiClient.openGuestSession(base(),
                "not-a-real-invitation-token-000000000000000"))
                .isInstanceOfSatisfying(RemoteApiException.class,
                        e -> assertThat(e.serverCode()).isEqualTo("INVITATION_NOT_FOUND"));

        // expired (and a session opened before expiry stops working)
        Invite expiring = invite(freshEmail(), fingerprint(MockSigningToken.DEFAULT_ALIAS));
        String live = WorkflowApiClient.openGuestSession(base(), expiring.token()).accessToken();
        InvitationEntity inv = latestInvitation(expiring.workflowId());
        inv.setExpiresAt(Instant.now().minusSeconds(60));
        invitationRepository.save(inv);
        assertThatThrownBy(() -> WorkflowApiClient.openGuestSession(base(), expiring.token()))
                .isInstanceOfSatisfying(RemoteApiException.class,
                        e -> assertThat(e.serverCode()).isEqualTo("INVITATION_EXPIRED"));
        assertThat(call("GET", "/api/workflows/" + expiring.workflowId() + "/task", live))
                .isEqualTo(403);

        // replaced by a resend: the old link no longer works
        Invite resent = invite(freshEmail(), fingerprint(MockSigningToken.DEFAULT_ALIAS));
        invitations.resend(latestInvitation(resent.workflowId()).getId(), "alice");
        assertThatThrownBy(() -> WorkflowApiClient.openGuestSession(base(), resent.token()))
                .isInstanceOfSatisfying(RemoteApiException.class,
                        e -> assertThat(e.serverCode()).isEqualTo("INVITATION_REVOKED"));
    }

    /* ------------------------------------------------------------------ */

    private record Invite(String workflowId, String token) {
    }

    private Invite invite(String email, String fingerprint) {
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("guest-contract.pdf", "SEQUENTIAL",
                        List.of(new CreateWorkflowRequest.SignerSpec(1, email, fingerprint))),
                "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), "alice");
        List<InvitationLinkView> sent = coordinator.invite(view.workflowId(),
                prep.pendingInvites(), "alice");
        String url = sent.get(0).invitationUrl();
        return new Invite(view.workflowId(), url.substring(url.lastIndexOf('/') + 1));
    }

    private InvitationEntity latestInvitation(String workflowPublicId) {
        Long id = workflowRepository.findByWorkflowId(workflowPublicId).orElseThrow().getId();
        return invitationRepository.findByWorkflowId(id).stream()
                .max(java.util.Comparator.comparing(InvitationEntity::getId)).orElseThrow();
    }

    private int call(String method, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + bearer);
        b = "POST".equals(method)
                ? b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                : b.GET();
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String fingerprint(String alias) {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        String fp = tokenService.getCertificates().stream()
                .filter(c -> alias.equals(c.getAlias()))
                .map(SigningCertificate::getSha256Fingerprint)
                .findFirst().orElseThrow();
        tokenService.disconnect();
        simulator.removeToken();
        return fp;
    }

    private static String freshEmail() {
        return "guest" + System.nanoTime() + "@example.test";
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
