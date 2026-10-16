package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.auth.DbUserAccounts;
import com.example.signer.server.db.entity.AccountRole;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.entity.WorkflowSignerEntity;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.db.repository.WorkflowSignerRepository;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.RegistrationService;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;

import com.example.signer.server.db.JpaWorkflowStore;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live-PostgreSQL tests (brief Phase 1/§49): exercise the {@link JpaWorkflowStore}
 * persistence and the invitation/registration flow against a real PostgreSQL
 * instance. Skipped (never failed) when the database is not reachable or no
 * credentials are configured — set SIGNER_DB_USERNAME / SIGNER_DB_PASSWORD
 * (and optionally SIGNER_DB_URL, default localhost:5432) to enable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-test",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf(value = "com.example.signer.server.workflow.PostgresWorkflowStoreLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class PostgresWorkflowStoreLiveTests {

    @Autowired
    private WorkflowStore store;                 // must be the JPA implementation

    @Autowired
    private JpaWorkflowStore jpaStore;   // same bean, typed for restart checks

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowSignerRepository signerRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.example.signer.server.db.repository.AuditEventRepository auditRepository;

    @Autowired
    private com.example.signer.server.invites.WorkflowInvitationCoordinator coordinator;

    @Autowired
    private InvitationService invitationService;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private com.example.signer.server.workflow.RemoteWorkflowService remoteWorkflowService;

    @Autowired
    private DbUserAccounts dbAccounts;

    /** The seeded dev account must be able to authenticate after seeding. */
    @Test
    @Order(1)
    void devSeedAccountsAreDatabaseBacked() {
        assertThat(dbAccounts.matches("alice", "alice-pass")).isTrue();
        assertThat(dbAccounts.matches("alice", "wrong")).isFalse();
        assertThat(dbAccounts.matches("bob", "bob-pass")).isTrue();
        // login by email must work too
        assertThat(dbAccounts.matches("alice@example.test", "alice-pass")).isTrue();
        assertThat(dbAccounts.knowsUser("alice")).isTrue();
        // V5: seeded accounts record when their password was set.
        assertThat(userRepository.findByUsername("alice"))
                .hasValueSatisfying(u -> {
                    assertThat(u.getPasswordChangedAt()).isNotNull();
                    assertThat(u.getPasswordHash()).startsWith("$2a$12$");
                });
        // V5: a username differing only in letter case is rejected by the
        // database (uq_users_username_ci).
        assertThatThrownBy(() -> userRepository.saveAndFlush(new UserEntity(
                "ALICE", "alice-upper@example.test", null, "Upper", AccountStatus.PENDING_INVITE)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(userRepository.existsByUsernameIgnoreCase("Alice")).isTrue();
    }

    /**
     * Full store round-trip: create → mutate → save → RELOAD THROUGH A FRESH
     * STORE INSTANCE (simulates a server restart) → identical snapshot; plus
     * the append-only revision contract and the blob operations.
     */
    @Test
    @Order(2)
    void aggregateSurvivesRestartInPostgres() {
        String workflowId = "WF-TEST-RESTART-" + System.nanoTime();

        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                workflowId, "contract.pdf", com.example.signer.workflow.WorkflowSigningPolicy.FLEXIBLE,
                List.of(WorkflowSignerRecord.bound(1, "alice", "a".repeat(64)),
                        WorkflowSignerRecord.unbound(2, "bob")),
                "alice");
        wf.appendAudit(AuditEventType.WORKFLOW_CREATED, "alice", "created");
        store.stageOriginal(workflowId, "original-bytes".getBytes());
        store.save(wf);

        // ── restart: load through a fresh store instance ─────────────────
        SigningWorkflowAggregate reloaded = jpaStore.load(workflowId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.documentName()).isEqualTo("contract.pdf");
        assertThat(reloaded.policy()).isEqualTo(com.example.signer.workflow.WorkflowSigningPolicy.FLEXIBLE);
        assertThat(reloaded.status()).isEqualTo(WorkflowState.DRAFT);
        assertThat(reloaded.createdBy()).isEqualTo("alice");
        assertThat(reloaded.signers()).hasSize(2);
        assertThat(reloaded.signers().get(0).userId()).isEqualTo("alice");
        assertThat(reloaded.signers().get(0).isBound()).isTrue();
        assertThat(reloaded.signers().get(1).isBound()).isFalse();
        assertThat(reloaded.audit()).hasSize(1);
        assertThat(store.readStagedOriginal(workflowId)).isEqualTo("original-bytes".getBytes());

        // ── mutate: bind last certificate, prepare revision 0, claim turn ─
        reloaded.bindCertificate("bob", "b".repeat(64));
        reloaded.replaceRevision0(new DocumentRevisionRecord(0, -1,
                "c".repeat(64), 42, Instant.now(), "bob"));
        reloaded.transition(WorkflowState.READY_FOR_SIGNING);
        reloaded.transition(WorkflowState.WAITING_FOR_SIGNER);
        reloaded.claimTurn("bob");
        reloaded.appendAudit(AuditEventType.CERTIFICATE_BOUND, "bob", "bound");
        store.writeRevision(workflowId, 0, "revision-zero".getBytes());
        store.save(reloaded);

        SigningWorkflowAggregate afterMutation = jpaStore.load(workflowId);
        assertThat(afterMutation.status()).isEqualTo(WorkflowState.WAITING_FOR_SIGNER);
        assertThat(afterMutation.claimedTurnUserId()).isEqualTo("bob");
        assertThat(afterMutation.signers().get(1).certificateSha256()).isEqualTo("b".repeat(64));
        assertThat(afterMutation.signers().get(1).isBound()).isTrue();
        assertThat(afterMutation.currentRevision().sha256()).isEqualTo("c".repeat(64));
        assertThat(afterMutation.currentRevision().sizeBytes()).isEqualTo(42);
        assertThat(afterMutation.audit()).hasSize(2);
        assertThat(store.readRevision(workflowId, 0)).isEqualTo("revision-zero".getBytes());
        assertThat(store.existsRevision(workflowId, 0)).isTrue();

        // Append-only: rewriting revision 0 is refused.
        assertThatThrownBy(() -> store.writeRevision(workflowId, 0, "x".getBytes()))
                .isInstanceOf(Exception.class);

        // Listing finds the workflow.
        assertThat(store.listWorkflowIds()).contains(workflowId);

        // Relational shape: exactly one workflow row, two signer rows,
        // one document version, two audit events.
        var row = workflowRepository.findByWorkflowId(workflowId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("WAITING_FOR_SIGNER");
        assertThat(row.getClaimedTurnUsername()).isEqualTo("bob");
        List<WorkflowSignerEntity> signerRows =
                signerRepository.findByWorkflowIdOrderBySignerOrder(row.getId());
        assertThat(signerRows).hasSize(2);
        assertThat(signerRows.get(1).getCertificateSha256()).isEqualTo("b".repeat(64));
        assertThat(auditRepository.findByWorkflowIdOrderByEventSeq(row.getId()))
                .hasSize(2);

        // Unknown id → null, never an exception.
        assertThat(store.load("WF-DOES-NOT-EXIST")).isNull();
        assertThat(store.exists("WF-DOES-NOT-EXIST")).isFalse();
    }

    /**
     * Email invitations at workflow creation (brief §13/§16/§53) plus the
     * registration lifecycle: placeholder account → invitation with hashed
     * token → registration activates the account → acceptance recorded.
     */
    @Test
    @Order(3)
    void emailSignerGetsInvitationAndRegistersThroughToken() {
        String uniqueEmail = "dave" + System.nanoTime() + "@Example.COM";

        // ── prepare: email-shaped slot → placeholder account + rewrite ───
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("invite-test.pdf",
                        List.of(new CreateWorkflowRequest.SignerSpec(1, "alice",
                                        "a".repeat(64)),
                                new CreateWorkflowRequest.SignerSpec(2, uniqueEmail, null))),
                "alice");
        // Username derived from the email local part; email normalized.
        assertThat(prep.request().signers().get(1).userId()).startsWith("dave");
        assertThat(prep.pendingInvites()).hasSize(1);
        assertThat(prep.pendingInvites().get(0).email()).isEqualTo(uniqueEmail.toLowerCase());
        assertThat(userRepository.findByUsername(prep.pendingInvites().get(0).username()))
                .hasValueSatisfying(u -> {
                    assertThat(u.getStatus()).isEqualTo(AccountStatus.PENDING_INVITE);
                    assertThat(u.getPasswordHash()).isNull();
                    // V5 defaults for a new invitation identity.
                    assertThat(u.getRole()).isEqualTo(AccountRole.USER);
                    assertThat(u.getEmailVerifiedAt()).isNull();
                    assertThat(u.getPasswordChangedAt()).isNull();
                    assertThat(u.getFailedLoginCount()).isZero();
                    assertThat(u.getLockedUntil()).isNull();
                    assertThat(u.getEmail()).isEqualTo(uniqueEmail.toLowerCase());
                });

        // Unknown plain usernames are rejected.
        assertThatThrownBy(() -> coordinator.prepare(new CreateWorkflowRequest(
                        "x.pdf", List.of(new CreateWorkflowRequest.SignerSpec(1, "ghost", null))),
                "alice"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown workflow user");

        // ── create the workflow and invite ────────────────────────────────
        var view = remoteWorkflowService.createWorkflow(prep.request(),
                "%PDF-1.4 invitation test".getBytes(), "alice");
        assertThat(view.status()).isEqualTo("DRAFT");
        List<com.example.signer.server.api.ApiContracts.InvitationLinkView>
                emailed = coordinator.invite(view.workflowId(),
                        prep.pendingInvites(), "alice");

        var invitationRow = invitationRepository
                .findByWorkflowId(workflowRepository.findByWorkflowId(view.workflowId())
                        .orElseThrow().getId())
                .get(0);
        assertThat(invitationRow.getStatus()).isEqualTo("PENDING");
        assertThat(invitationRow.getEmail()).isEqualTo(uniqueEmail.toLowerCase());
        // Only the HASH is persisted — 64 hex chars, never the raw token.
        assertThat(invitationRow.getTokenHash()).matches("[0-9a-f]{64}");
        // A delivered invitation is marked SENT (the failure side is covered
        // by InvitationEmailFailureTests).
        assertThat(invitationRow.getEmailStatus()).isEqualTo("SENT");

        // The sender is handed the exact link that was emailed — built on the
        // server's resolved base URL, and carrying the very token whose hash
        // was persisted. Without this the sender cannot tell a signer which
        // link to open, and a loopback base would go unnoticed.
        assertThat(emailed).hasSize(1);
        assertThat(emailed.get(0).signerOrder()).isEqualTo(2);
        assertThat(emailed.get(0).email()).isEqualTo(uniqueEmail.toLowerCase());
        String emailedUrl = emailed.get(0).invitationUrl();
        assertThat(emailedUrl).startsWith(
                invitationService.linkResolver().baseUrl() + "/invitations/");
        assertThat(sha256Hex(emailedUrl.substring(emailedUrl.lastIndexOf('/') + 1)))
                .isEqualTo(invitationRow.getTokenHash());

        // ── registration: fresh invitation → activate → acceptance ───────
        String rawToken = invitationService.invite(view.workflowId(), 2,
                uniqueEmail, "alice");
        String username = registrationService.register(rawToken, "Dave D.",
                "password-123");
        assertThat(username).startsWith("dave");
        assertThat(dbAccounts.matches(username, "password-123")).isTrue();
        assertThat(userRepository.findByUsername(username))
                .hasValueSatisfying(u -> {
                    assertThat(u.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                    // Redeeming the emailed invitation proves the address.
                    assertThat(u.getEmailVerifiedAt()).isNotNull();
                    assertThat(u.getPasswordChangedAt()).isNotNull();
                    assertThat(u.getRole()).isEqualTo(AccountRole.USER);
                    // New hashes use cost 12.
                    assertThat(u.getPasswordHash()).startsWith("$2a$12$");
                });

        // Token is single-use.
        assertThatThrownBy(() -> registrationService.register(rawToken, "Again",
                "password-123"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already");

        // The superseded first invitation stays REVOKED; the accepted one
        // records who accepted.
        List<InvitationEntity> allInvitations = invitationRepository
                .findByWorkflowId(workflowRepository.findByWorkflowId(view.workflowId())
                        .orElseThrow().getId());
        assertThat(allInvitations).anySatisfy(i -> {
            assertThat(i.getStatus()).isEqualTo("ACCEPTED");
            assertThat(i.getAcceptedBy()).isEqualTo(username);
        });
        assertThat(allInvitations).anySatisfy(i ->
                assertThat(i.getStatus()).isEqualTo("REVOKED"));

        // Unknown tokens are rejected without leaking information.
        assertThatThrownBy(() -> registrationService.register("bogus-token",
                "X", "password-123"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");

        // Password policy enforced.
        String secondToken = invitationService.invite(view.workflowId(), 2,
                uniqueEmail, "alice");
        assertThatThrownBy(() -> registrationService.register(secondToken,
                "X", "short"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("8");
    }

    /** Same digest the service stores: SHA-256 of the raw token, hex. */
    private static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Probe used by {@code @EnabledIf}: reachable server + credentials present. */
    public static boolean postgresReachable() {
        String url = env("SIGNER_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
        String username = env("SIGNER_DB_USERNAME", "postgres");
        String password = env("SIGNER_DB_PASSWORD", "");
        try {
            // Self-provisioning (test-only): (re)create the test database so
            // every run starts from a clean schema. V1 has been modified
            // during development, so a stale Flyway history must not leak in.
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS signer_test WITH (FORCE)");
                statement.execute("CREATE DATABASE signer_test");
            } catch (Exception e) {
                // Provisioning failed (e.g. no CREATE privilege or active
                // connections) — fall through: an existing signer_test may
                // still be perfectly usable.
            }
            try (Connection ignored = DriverManager.getConnection(
                    url.replaceFirst("/[^/?]+(?:\\?.*)?$", "/signer_test"),
                    username, password)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
