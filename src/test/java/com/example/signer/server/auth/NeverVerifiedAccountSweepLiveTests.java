package com.example.signer.server.auth;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.server.workflow.RemoteWorkflowService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase D1 risk D11: the sweep that deletes accounts which never
 * proved an e-mail address and were never used.
 *
 * <p>This is the only statement in the application that removes a
 * {@code users} row, so there is a test per <b>guard</b> rather than one
 * happy-path test: an invitation identity, a disabled account, one that has
 * been signed in to, one younger than the retention window, one holding a
 * signer slot, one owning a workflow and one holding a claimed turn must all
 * survive it. Three of those are also protected by foreign keys that are
 * {@code NO ACTION} — the tests prove the sweep <b>skips</b> them rather than
 * failing on them, which is the difference between a sweep that works and one
 * that throws every night.
 *
 * <p>Ages are moved in the test database rather than waiting a week.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-d11-sweep-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + NeverVerifiedAccountSweepLiveTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.web.unverified-retention=P7D"
        })
@EnabledIf(value = "com.example.signer.server.auth.NeverVerifiedAccountSweepLiveTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class NeverVerifiedAccountSweepLiveTests {

    static final String DB = "signflow_d11_sweep_test";

    @Autowired
    private UserRepository users;

    @Autowired
    private DbUserAccounts accounts;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private DataSource dataSource;

    public static boolean freshDatabase() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DB);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ------------------------------ helpers ----------------------------- */

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static String newName() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * An account in exactly the state a website sign-up leaves behind:
     * active, with a password, no verified address, never signed in.
     */
    private String unverifiedSignUp() {
        String username = newName();
        UserEntity account = new UserEntity(username, username + "@example.test",
                PasswordHashing.hash("a-strong-password-9"), "Ada", AccountStatus.ACTIVE);
        users.save(account);
        return username;
    }

    /** Moves an account's creation date into the past. */
    private void age(String username, Duration by) {
        assertThat(jdbc().update("UPDATE users SET created_at ="
                + " now() - (CAST(? AS int) * INTERVAL '1 day') WHERE username = ?",
                (int) by.toDays(), username)).isEqualTo(1);
    }

    /** Takes a verified account's verification away again. */
    private void unverify(String username) {
        assertThat(jdbc().update("UPDATE users SET email_verified_at = NULL"
                + " WHERE username = ?", username)).isEqualTo(1);
    }

    private boolean exists(String username) {
        return users.findByUsername(username).isPresent();
    }

    /** alice's workflow, with {@code signer} holding slot 1. */
    private String workflowSignedBy(String owner, String signer) {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        specs.add(new CreateWorkflowRequest.SignerSpec(1, signer, null));
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("d11.pdf", "SEQUENTIAL", specs), owner);
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), owner);
        return view.workflowId();
    }

    private static byte[] pdf() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A verified owner, so it is never itself a candidate for the sweep. */
    private String verifiedOwner() {
        String username = unverifiedSignUp();
        users.findByUsername(username).ifPresent(u -> {
            u.setEmailVerifiedAt(Instant.now());
            users.save(u);
        });
        return username;
    }

    /* ------------------------- what must be deleted --------------------- */

    @Test
    void anAbandonedSignUpIsDeletedWithItsTokens() {
        String username = unverifiedSignUp();
        jdbc().update("INSERT INTO email_verification_tokens (token_hash, username, email,"
                + " created_at, expires_at) VALUES (?, ?, ?, now(), now() + INTERVAL '1 day')",
                "c".repeat(64), username, username + "@example.test");
        age(username, Duration.ofDays(8));

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(username)).as("an abandoned sign-up is deleted").isFalse();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM email_verification_tokens"
                        + " WHERE username = ?", Long.class, username))
                .as("its tokens cascade with it").isZero();
    }

    /* -------------------------- what must survive ----------------------- */

    @Test
    void aVerifiedAccountSurvives() {
        String username = verifiedOwner();
        age(username, Duration.ofDays(400));

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(username)).isTrue();
    }

    @Test
    void anInvitationIdentitySurvives() {
        // The dangerous case: a PENDING_INVITE placeholder has no verified
        // address and no password either, so only the status tells it apart
        // from an abandoned sign-up.
        String username = newName();
        UserEntity placeholder = new UserEntity(username, username + "@example.test",
                null, "Invited", AccountStatus.PENDING_INVITE);
        users.save(placeholder);
        age(username, Duration.ofDays(400));

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(username)).as("an invited signer must never be swept").isTrue();
    }

    @Test
    void aDisabledAccountSurvives() {
        String username = unverifiedSignUp();
        users.findByUsername(username).ifPresent(u -> {
            u.setStatus(AccountStatus.DISABLED);
            users.save(u);
        });
        age(username, Duration.ofDays(400));

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(username)).as("an operator's decision is not housekeeping").isTrue();
    }

    @Test
    void anAccountThatHasBeenSignedInToSurvives() {
        String username = unverifiedSignUp();
        age(username, Duration.ofDays(400));
        assertThat(jdbc().update("UPDATE users SET last_login_at = now() WHERE username = ?",
                username)).isEqualTo(1);

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(username)).isTrue();
    }

    @Test
    void anAccountInsideTheRetentionWindowSurvives() {
        String young = unverifiedSignUp();
        age(young, Duration.ofDays(6));

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(young)).as("six days is inside the seven-day window").isTrue();
    }

    @Test
    void anAccountHoldingASignerSlotSurvives() {
        String owner = verifiedOwner();
        String signer = unverifiedSignUp();
        workflowSignedBy(owner, signer);
        age(signer, Duration.ofDays(400));

        // Must not throw: the foreign key would refuse this delete, so the
        // sweep has to skip the row rather than fail the whole statement.
        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(signer)).as("a signer slot keeps its account alive").isTrue();
    }

    @Test
    void anAccountOwningAWorkflowSurvives() {
        // Created while verified, because an unverified account cannot start
        // a signing request at all (risk D4); the verification is taken away
        // afterwards, which is the state this guard is about.
        String owner = verifiedOwner();
        workflowSignedBy(owner, verifiedOwner());
        unverify(owner);
        age(owner, Duration.ofDays(400));

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(owner)).as("the creator of a workflow is kept").isTrue();
    }

    @Test
    void anAccountHoldingAClaimedTurnSurvives() {
        String owner = verifiedOwner();
        String claimer = unverifiedSignUp();
        String workflowId = workflowSignedBy(owner, verifiedOwner());
        assertThat(jdbc().update("UPDATE workflows SET claimed_turn_username = ?,"
                + " claimed_turn_at = now() WHERE workflow_id = ?", claimer, workflowId))
                .isEqualTo(1);
        age(claimer, Duration.ofDays(400));

        accounts.deleteNeverVerifiedAccounts();

        assertThat(exists(claimer)).as("a claimed turn keeps its account alive").isTrue();
    }

    /* ------------------------------ the off switch ---------------------- */

    @Test
    void aZeroRetentionSwitchesTheSweepOff() {
        String username = unverifiedSignUp();
        age(username, Duration.ofDays(400));

        new DbUserAccounts(users, DbUserAccounts.DEFAULT_MAX_FAILURES,
                DbUserAccounts.DEFAULT_LOCK.toMinutes(), Duration.ZERO)
                .deleteNeverVerifiedAccounts();

        assertThat(exists(username)).as("zero retention deletes nothing").isTrue();
        // ...and the configured sweep still would.
        accounts.deleteNeverVerifiedAccounts();
        assertThat(exists(username)).isFalse();
    }
}
