package com.example.signer.server.auth;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.db.repository.DeniedTokenRepository;

import org.junit.jupiter.api.BeforeEach;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 risk L4 against a real PostgreSQL: the deny-list in
 * {@code denied_access_tokens} (V12).
 *
 * <p>The rules themselves are covered by {@link AccessTokenDenyListTests}
 * against the in-memory list, so they are not re-tested here. What is tested
 * here is what the database implementation is FOR, and what only it can get
 * wrong:
 *
 * <ul>
 *   <li>database mode really resolves to this implementation — a deployment
 *       that silently got the in-memory one would believe a logout reached
 *       its other instances when it did not;</li>
 *   <li>the entry is in the <b>table</b> and not in the object, so another
 *       instance over the same database sees it;</li>
 *   <li>a second logout of the same token is one row, which is what
 *       {@code ON CONFLICT DO NOTHING} is for;</li>
 *   <li>the sweep deletes what is long dead and leaves a live entry alone.</li>
 * </ul>
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-l4-deny-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + DbAccessTokenDenyListLiveTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}"
        })
@EnabledIf(value = "com.example.signer.server.auth.DbAccessTokenDenyListLiveTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class DbAccessTokenDenyListLiveTests {

    static final String DB = "signflow_l4_deny_test";

    @Autowired
    private AccessTokenDenyList wired;

    @Autowired
    private DeniedTokenRepository repository;

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

    @BeforeEach
    void emptyTheList() {
        jdbc().update("DELETE FROM denied_access_tokens");
    }

    /* ------------------------------------------------------------------ */

    @Test
    void databaseModeResolvesToTheSharedListAndNotTheHeap() {
        assertThat(this.wired)
                .as("a deployment with a database must not get the per-instance list")
                .isInstanceOf(DbAccessTokenDenyList.class);
    }

    @Test
    void aDeniedTokenIsInTheTableAndAnotherInstanceSeesIt() {
        String jti = UUID.randomUUID().toString();

        this.wired.deny(jti, Instant.now().plus(Duration.ofMinutes(15)));

        assertThat(rows(jti)).as("one row, written by the logout").isEqualTo(1);
        assertThat(this.wired.isDenied(jti)).isTrue();

        // A second instance of the server holds no deny-list state of its
        // own; everything it knows it reads from this table.
        AccessTokenDenyList otherInstance = new DbAccessTokenDenyList(this.repository);
        assertThat(otherInstance.isDenied(jti))
                .as("a logout handled by one instance has to reach the others")
                .isTrue();
        assertThat(otherInstance.isDenied(UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void theRowRecordsTheTokenIdAndItsExpiryAndNothingAboutTheAccount() {
        String jti = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plus(Duration.ofMinutes(15));

        this.wired.deny(jti, expiry);

        assertThat(jdbc().queryForObject(
                "SELECT token_id FROM denied_access_tokens WHERE token_id = ?",
                String.class, jti))
                .as("the jti as it is, not a hash of it: a row here grants nothing")
                .isEqualTo(jti);
        assertThat(jdbc().queryForObject("SELECT expires_at FROM denied_access_tokens"
                + " WHERE token_id = ?", OffsetDateTime.class, jti).toInstant())
                .as("the token's own exp, so the entry cannot outlive what it denies")
                .isBetween(expiry.minusMillis(1), expiry.plusMillis(1));
    }

    @Test
    void signingOutTwiceWithTheSameTokenIsOneRow() {
        String jti = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plus(Duration.ofMinutes(15));

        this.wired.deny(jti, expiry);
        this.wired.deny(jti, expiry);
        this.wired.deny(jti, expiry.plusSeconds(60));

        assertThat(rows(jti))
                .as("ON CONFLICT DO NOTHING: idempotent, and not a constraint violation")
                .isEqualTo(1);
        assertThat(this.wired.isDenied(jti)).isTrue();
    }

    @Test
    void aTokenThatHasAlreadyExpiredIsNotWrittenAtAll() {
        // Which is also the only row the table's CHECK would have refused:
        // expires_at must be after denied_at.
        String jti = UUID.randomUUID().toString();

        this.wired.deny(jti, Instant.now().minusSeconds(1));

        assertThat(rows(jti)).isZero();
        assertThat(this.wired.isDenied(jti)).isFalse();
    }

    @Test
    void anIdTooLongForTheColumnIsRefusedBeforeItReachesIt() {
        // Unreachable for a token this server issued. It must not become a
        // DataIntegrityViolationException thrown into a logout, and it must
        // not be truncated into an id that could never match.
        String tooLong = "j".repeat(AccessTokenDenyList.MAX_TOKEN_ID_LENGTH + 1);

        this.wired.deny(tooLong, Instant.now().plus(Duration.ofMinutes(15)));

        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM denied_access_tokens", Integer.class)).isZero();
    }

    @Test
    void theSweepDeletesWhatIsLongDeadAndLeavesALiveEntryAlone() {
        String live = UUID.randomUUID().toString();
        this.wired.deny(live, Instant.now().plus(Duration.ofMinutes(15)));

        // Aged in SQL rather than by waiting: the retention is an hour past
        // the token's own expiry, so the row has to look a day old.
        String swept = UUID.randomUUID().toString();
        Instant longAgo = Instant.now().minus(Duration.ofDays(1));
        jdbc().update("INSERT INTO denied_access_tokens (token_id, denied_at, expires_at)"
                        + " VALUES (?, ?, ?)", swept,
                OffsetDateTime.ofInstant(longAgo, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(longAgo.plusSeconds(900), ZoneOffset.UTC));

        ((DbAccessTokenDenyList) this.wired).deleteExpired();

        assertThat(rows(swept))
                .as("the token it denied expired a day ago; the decoder refuses it anyway")
                .isZero();
        assertThat(rows(live)).as("and nothing live is touched").isEqualTo(1);
        assertThat(this.wired.isDenied(live)).isTrue();
    }

    /* ------------------------------ helpers ----------------------------- */

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(this.dataSource);
    }

    private int rows(String tokenId) {
        Integer count = jdbc().queryForObject("SELECT count(*) FROM denied_access_tokens"
                + " WHERE token_id = ?", Integer.class, tokenId);
        return count == null ? 0 : count;
    }
}
