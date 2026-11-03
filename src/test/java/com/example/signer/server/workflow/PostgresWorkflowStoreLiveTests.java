package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.auth.DbUserAccounts;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.entity.WorkflowSignerEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.db.repository.WorkflowSignerRepository;

import com.example.signer.server.db.JpaWorkflowStore;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live-PostgreSQL tests (brief Phase 1/§49): exercise the {@link JpaWorkflowStore}
 * persistence against a real PostgreSQL instance. Skipped (never failed) when the database is not reachable or no
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
    private UserRepository userRepository;

    @Autowired
    private com.example.signer.server.db.repository.AuditEventRepository auditRepository;

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
                "ALICE", "alice-upper@example.test", null, "Upper", AccountStatus.PLACEHOLDER)))
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
                workflowId, "contract.pdf", WorkflowSignerRecord.unbound(1, "alice"),
                "alice");
        wf.appendAudit(AuditEventType.WORKFLOW_CREATED, "alice", "created");
        store.stageOriginal(workflowId, "original-bytes".getBytes());
        store.save(wf);

        // ── restart: load through a fresh store instance ─────────────────
        SigningWorkflowAggregate reloaded = jpaStore.load(workflowId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.documentName()).isEqualTo("contract.pdf");
        assertThat(reloaded.status()).isEqualTo(WorkflowState.DRAFT);
        assertThat(reloaded.createdBy()).isEqualTo("alice");
        assertThat(reloaded.signers()).hasSize(1);
        assertThat(reloaded.signer().userId()).isEqualTo("alice");
        assertThat(reloaded.signer().isBound()).isFalse();
        assertThat(reloaded.audit()).hasSize(1);
        assertThat(store.readStagedOriginal(workflowId)).isEqualTo("original-bytes".getBytes());

        // ── mutate: bind the certificate and prepare revision 0 ──────────
        reloaded.bindCertificate("alice", "b".repeat(64));
        reloaded.replaceRevision0(new DocumentRevisionRecord(0, -1,
                "c".repeat(64), 42, Instant.now(), "alice"));
        reloaded.transition(WorkflowState.READY_FOR_SIGNING);
        reloaded.transition(WorkflowState.WAITING_FOR_SIGNER);
        reloaded.appendAudit(AuditEventType.CERTIFICATE_BOUND, "alice", "bound");
        store.writeRevision(workflowId, 0, "revision-zero".getBytes());
        store.save(reloaded);

        SigningWorkflowAggregate afterMutation = jpaStore.load(workflowId);
        assertThat(afterMutation.status()).isEqualTo(WorkflowState.WAITING_FOR_SIGNER);
        assertThat(afterMutation.signer().certificateSha256()).isEqualTo("b".repeat(64));
        assertThat(afterMutation.signer().isBound()).isTrue();
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

        // Relational shape: exactly one workflow row, one signer row,
        // one document version, two audit events.
        var row = workflowRepository.findByWorkflowId(workflowId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("WAITING_FOR_SIGNER");
        List<WorkflowSignerEntity> signerRows =
                signerRepository.findByWorkflowIdOrderBySignerOrder(row.getId());
        assertThat(signerRows).hasSize(1);
        assertThat(signerRows.get(0).getCertificateSha256()).isEqualTo("b".repeat(64));
        assertThat(auditRepository.findByWorkflowIdOrderByEventSeq(row.getId()))
                .hasSize(2);

        // Unknown id → null, never an exception.
        assertThat(store.load("WF-DOES-NOT-EXIST")).isNull();
        assertThat(store.exists("WF-DOES-NOT-EXIST")).isFalse();
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
