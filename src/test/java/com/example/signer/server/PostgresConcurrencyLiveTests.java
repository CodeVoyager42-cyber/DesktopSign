package com.example.signer.server;

import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.auth.AuthController;
import com.example.signer.server.db.AuditLog;
import com.example.signer.server.db.entity.AuditEventEntity;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.RegistrationService;
import com.example.signer.server.invites.SignPageController;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live-PostgreSQL regression tests for P0.4/P0.5/P0.7/P0.10/P0.13/P0.15:
 * JPA optimistic locking, row-locked mutations, database-backed audit
 * sequencing, invitation token rules, the removed bearer-token document
 * download and refresh after an account is disabled. Skipped (never failed)
 * when PostgreSQL is unreachable. Uses its OWN Spring context and a
 * non-destructive availability probe: the probe of the older live tests
 * drops and recreates {@code signer_test}, which would pull the database
 * from under a context cached by another class.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-concurrency",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}"
        })
@Import(InvitationSignerLifecycleTests.CapturedMailbox.class)
@EnabledIf(value = "com.example.signer.server.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class PostgresConcurrencyLiveTests {

    @Autowired
    private WorkflowStore store;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthController auth;

    @Autowired
    private SignPageController signPage;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void tearDown() {
        tokenService.disconnect();
        simulator.removeToken();
        InvitationSignerLifecycleTests.CapturedMailbox.LINKS.clear();
    }

    /* ── P0.5: optimistic locking ──────────────────────────────────────── */

    /**
     * Task lists and owner views query only the caller's workflows (one
     * indexed query) instead of loading every workflow — and still return
     * exactly the right ones.
     */
    @Test
    void listsQueryOnlyTheCallersWorkflows() {
        String both = createWorkflow();                    // alice + bob, created by alice
        String charlieOnly = workflows.createWorkflow(new CreateWorkflowRequest("solo.pdf",
                "SEQUENTIAL", List.of(new CreateWorkflowRequest.SignerSpec(1, "charlie", null))),
                pdf(), "bob").workflowId();

        assertThat(store.listWorkflowIdsForSigner("bob")).contains(both).doesNotContain(charlieOnly);
        assertThat(store.listWorkflowIdsForSigner("charlie")).contains(charlieOnly).doesNotContain(both);
        assertThat(store.listWorkflowIdsCreatedBy("bob")).contains(charlieOnly).doesNotContain(both);
        assertThat(store.listWorkflowIdsCreatedBy("alice")).contains(both).doesNotContain(charlieOnly);
        assertThat(workflows.listParticipating("charlie").tasks())
                .extracting(t -> t.workflowId()).contains(charlieOnly).doesNotContain(both);
        assertThat(workflows.ownedBy("bob")).extracting(w -> w.workflowId())
                .contains(charlieOnly).doesNotContain(both);
    }

    @Test
    void aStaleAggregateIsRejectedByTheVersionCheck() {
        String id = createWorkflow();
        long before = version(id);
        SigningWorkflowAggregate first = store.load(id);
        SigningWorkflowAggregate stale = store.load(id);

        first.appendAudit(AuditEventType.WORKFLOW_CREATED, "alice", "winner");
        store.save(first);
        assertThat(version(id)).isEqualTo(before + 1);

        stale.appendAudit(AuditEventType.WORKFLOW_CREATED, "bob", "stale");
        assertThatThrownBy(() -> store.save(stale))
                .isInstanceOf(WorkflowConflictException.class);
        assertThat(version(id)).isEqualTo(before + 1);
        assertThat(auditDetails(id)).contains("winner").doesNotContain("stale");
    }

    /* ── P0.4 in database mode: concurrent submissions ─────────────────── */

    @Test
    void concurrentSubmissionsCommitOneRevisionAndUniqueAuditSequences() throws Exception {
        String id = createWorkflow();
        byte[] signed = signLocally(workflows.getDocument(id, "alice"), 1);

        List<Object> outcomes = race(4, i -> () -> workflows.submitSignature(id, "alice",
                signed, new SignatureSubmissionRequest(0, null, "op-db-" + i)));

        assertThat(outcomes.stream().filter(o -> !(o instanceof ApiException))).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o instanceof ApiException)
                .map(o -> ((ApiException) o).code()))
                .allSatisfy(code -> assertThat(code).isIn(ApiErrorCode.STALE_REVISION,
                        ApiErrorCode.NOT_YOUR_TURN, ApiErrorCode.INVALID_WORKFLOW_STATE,
                        ApiErrorCode.WORKFLOW_CONFLICT, ApiErrorCode.REVISION_CONFLICT));
        assertThat(store.load(id).revisions()).hasSize(2);
        assertThat(store.existsRevision(id, 2)).isFalse();
        assertContiguousAudit(id);
    }

    /* ── P0.7: audit sequencing under concurrent writers ──────────────── */

    @Test
    void concurrentAuditWritersNeverCollideOrLoseEvents() throws Exception {
        String id = createWorkflow();
        Long dbId = workflowRepository.findByWorkflowId(id).orElseThrow().getId();
        int before = auditRepository.findByWorkflowIdOrderByEventSeq(dbId).size();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int writers = 8;
        int perWriter = 5;

        List<Object> outcomes = race(writers + 1, i -> () -> {
            if (i == writers) {
                // A workflow-aggregate save racing the direct audit writers.
                synchronized (store.lockFor(id)) {
                    SigningWorkflowAggregate wf = store.load(id);
                    wf.appendAudit(AuditEventType.WORKFLOW_CREATED, "alice", "aggregate");
                    store.save(wf);
                }
                return null;
            }
            for (int k = 0; k < perWriter; k++) {
                final int n = k;
                tx.executeWithoutResult(status -> auditLog.append(dbId,
                        AuditEventType.WORKFLOW_CREATED, "writer-" + i, "event " + n));
            }
            return null;
        });

        assertThat(outcomes).as("no writer failed").containsOnlyNulls();
        List<AuditEventEntity> events = auditRepository.findByWorkflowIdOrderByEventSeq(dbId);
        assertThat(events).hasSize(before + writers * perWriter + 1);
        assertContiguousAudit(id);
    }

    /* ── P0.10 / P0.15: invitation token rules ────────────────────────── */

    @Test
    void expiredRevokedAndReusedInvitationsAreRefused() {
        // expired
        Invite expired = invite();
        InvitationEntity e = invitationOf(expired);
        e.setExpiresAt(Instant.now().minusSeconds(60));
        invitationRepository.save(e);
        assertThat(code(() -> invitations.redeemableByToken(expired.token())))
                .isEqualTo(ApiErrorCode.INVITATION_EXPIRED);
        assertThat(code(() -> registrationService.register(expired.token(), "X", "password-123")))
                .isIn(ApiErrorCode.INVITATION_EXPIRED, ApiErrorCode.INVITATION_REVOKED);

        // revoked
        Invite revoked = invite();
        invitations.revoke(invitationOf(revoked).getId(), "alice");
        assertThat(code(() -> invitations.redeemableByToken(revoked.token())))
                .isEqualTo(ApiErrorCode.INVITATION_REVOKED);
        assertThat(code(() -> registrationService.register(revoked.token(), "X", "password-123")))
                .isEqualTo(ApiErrorCode.INVITATION_REVOKED);

        // reused: accepted once by the recipient, then presented again
        Invite used = invite();
        String account = registrationService.register(used.token(), "Rita", "password-123");
        invitations.claim(used.token(), account);
        assertThat(code(() -> invitations.redeemableByToken(used.token())))
                .isEqualTo(ApiErrorCode.INVITATION_ALREADY_USED);
        assertThat(code(() -> registrationService.register(used.token(), "Mallory", "password-123")))
                .isIn(ApiErrorCode.INVITATION_ALREADY_USED, ApiErrorCode.BAD_REQUEST);
        assertThat(code(() -> invitations.claim(used.token(), "bob")))
                .isIn(ApiErrorCode.EMAIL_MISMATCH, ApiErrorCode.INVITATION_ALREADY_USED);
    }

    @Test
    void theInvitationLinkNoLongerServesTheDocument() {
        Invite live = invite();
        ResponseEntity<byte[]> response = signPage.document(live.token());
        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .doesNotStartWith("%PDF")
                .contains("Open the invitation");
        String page = signPage.page(live.token()).getBody();
        assertThat(page).doesNotContain("/document").doesNotContain("View the document");
    }

    /**
     * P0.4 for invitation mutations: the owner's revoke racing the
     * recipient's claim must never end as an ACCEPTED invitation when the
     * revoke reported success (that would be a lost update: a stale claim
     * overwriting the committed revoke).
     */
    @Test
    void revokeRacingClaimNeverResurrectsARevokedInvitation() throws Exception {
        for (int round = 0; round < 15; round++) {
            String username = "racer" + System.nanoTime();
            String email = username + "@example.test";
            userRepository.save(new UserEntity(username, email,
                    BCrypt.hashpw("password-123", BCrypt.gensalt(4)), "Racer", "ACTIVE"));
            Invite invite = invite(email);
            Long invitationId = invitationOf(invite).getId();

            List<Object> outcomes = race(2, i -> i == 0
                    ? () -> invitations.claim(invite.token(), username)
                    : () -> {
                        invitations.revoke(invitationId, "alice");
                        return "revoked";
                    });

            boolean claimed = !(outcomes.get(0) instanceof ApiException);
            boolean revoked = !(outcomes.get(1) instanceof ApiException);
            assertThat(revoked).as("the owner can always revoke").isTrue();
            String status = invitationRepository.findById(invitationId).orElseThrow()
                    .getStatus();
            assertThat(status).as("round %d (claim %s)", round,
                    claimed ? "succeeded" : "refused")
                    .isEqualTo(InvitationEntity.STATUS_REVOKED);
            if (!claimed) {
                assertThat(((ApiException) outcomes.get(0)).code())
                        .isEqualTo(ApiErrorCode.INVITATION_REVOKED);
            }
        }
    }

    /* ── P0.13: disabled account cannot refresh ───────────────────────── */

    @Test
    void aDisabledUserCannotRefreshItsSession() {
        String username = "disable" + System.nanoTime();
        userRepository.save(new UserEntity(username, username + "@example.test",
                BCrypt.hashpw("password-123", BCrypt.gensalt(4)), "To Disable", "ACTIVE"));

        ResponseEntity<TokenPairResponse> login =
                auth.login(new LoginRequest(username, "password-123"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String refresh = login.getBody().refreshToken();

        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        user.setStatus("DISABLED");
        userRepository.save(user);

        assertThat(auth.refresh(new AuthController.RefreshRequest(refresh))
                .getStatusCode().value()).isEqualTo(401);
        assertThat(auth.login(new LoginRequest(username, "password-123"))
                .getStatusCode().value()).isEqualTo(401);
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    /**
     * Non-destructive probe: PostgreSQL reachable and {@code signer_test}
     * present (created when missing). Never drops anything.
     */
    public static boolean postgresAvailable() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        String testUrl = url.replaceFirst("/[^/?]+(?:\\?.*)?$", "/signer_test");
        try (java.sql.Connection ignored = java.sql.DriverManager.getConnection(
                testUrl, user, password)) {
            return true;
        } catch (Exception missing) {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, user, password);
                 java.sql.Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE signer_test");
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private record Invite(String workflowId, String token) {
    }

    private Invite invite() {
        return invite("conc" + System.nanoTime() + "@example.test");
    }

    private Invite invite(String email) {
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("conc.pdf", "SEQUENTIAL",
                        List.of(new CreateWorkflowRequest.SignerSpec(1, email, null))),
                "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), "alice");
        List<InvitationLinkView> sent = coordinator.invite(view.workflowId(),
                prep.pendingInvites(), "alice");
        String url = sent.get(0).invitationUrl();
        return new Invite(view.workflowId(), url.substring(url.lastIndexOf('/') + 1));
    }

    private InvitationEntity invitationOf(Invite invite) {
        Long id = workflowRepository.findByWorkflowId(invite.workflowId()).orElseThrow().getId();
        return invitationRepository.findByWorkflowId(id).stream()
                .max(java.util.Comparator.comparing(InvitationEntity::getId))
                .orElseThrow();
    }

    private static ApiErrorCode code(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.code();
        }
        throw new AssertionError("expected an ApiException");
    }

    private String createWorkflow() {
        String fp = fingerprint(MockSigningToken.DEFAULT_ALIAS);
        String bobFp = fingerprint(MockSigningToken.THIRD_ALIAS);
        return workflows.createWorkflow(new CreateWorkflowRequest("conc.pdf", "SEQUENTIAL",
                List.of(new CreateWorkflowRequest.SignerSpec(1, "alice", fp),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", bobFp))),
                pdf(), "alice").workflowId();
    }

    private long version(String id) {
        return workflowRepository.findByWorkflowId(id).orElseThrow().getVersion();
    }

    private List<String> auditDetails(String id) {
        Long dbId = workflowRepository.findByWorkflowId(id).orElseThrow().getId();
        return auditRepository.findByWorkflowIdOrderByEventSeq(dbId).stream()
                .map(AuditEventEntity::getDetail).toList();
    }

    private void assertContiguousAudit(String id) {
        Long dbId = workflowRepository.findByWorkflowId(id).orElseThrow().getId();
        List<Long> seqs = auditRepository.findByWorkflowIdOrderByEventSeq(dbId).stream()
                .map(AuditEventEntity::getEventSeq).toList();
        assertThat(seqs).doesNotHaveDuplicates();
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
    }

    private interface TaskFactory {
        Callable<Object> task(int index);
    }

    private static List<Object> race(int n, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Object> task = factory.task(i);
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    results.add(f.get(120, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof ApiException api) {
                        results.add(api);
                    } else {
                        throw new AssertionError("unexpected failure: " + e.getCause(),
                                e.getCause());
                    }
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private byte[] signLocally(byte[] document, int order) throws Exception {
        Path dir = Files.createTempDirectory("db-conc");
        Path in = dir.resolve("in.pdf");
        Files.write(in, document);
        connectToken();
        Path out = dir.resolve("out.pdf");
        workflowSigningService.signNextStep(in, null, out, order);
        return Files.readAllBytes(out);
    }

    private String fingerprint(String alias) {
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
