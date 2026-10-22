package com.example.signer.server.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12 (the access-token deny-list, security Phase 2 risk L4) against a real
 * PostgreSQL, on a fresh database and on top of an existing V11 one that
 * already holds data. Own throwaway database ({@value #DB}). Skipped when
 * PostgreSQL is not reachable.
 *
 * <p>Same point as the V9, V10 and V11 tests: V12 is <b>additive</b>. It
 * creates one table and one index and touches nothing that was already
 * there.
 */
@EnabledIf(value = "com.example.signer.server.db.AccountIdentityMigrationLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_URL/SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class DeniedTokenMigrationLiveTests {

    private static final String DB = "signflow_v12_migration_test";

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String adminUrl() {
        return env("SIGNER_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    }

    private static String testUrl() {
        String admin = adminUrl();
        return admin.substring(0, admin.lastIndexOf('/') + 1) + DB;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, env("SIGNER_DB_USERNAME", "postgres"),
                env("SIGNER_DB_PASSWORD", ""));
    }

    private static void exec(String url, String sql) throws SQLException {
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = connect(testUrl()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(testUrl(), env("SIGNER_DB_USERNAME", "postgres"),
                        env("SIGNER_DB_PASSWORD", ""))
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        exec(adminUrl(), "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        exec(adminUrl(), "CREATE DATABASE " + DB);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        exec(adminUrl(), "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
    }

    @Test
    void v12AppliesOnAFreshDatabaseAndCreatesTheDenyList() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM flyway_schema_history"
                + " WHERE version = '12' AND success")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM denied_access_tokens")).isEqualTo("0");
    }

    @Test
    void theColumnsAreTheThreeTheListNeedsAndNothingElse() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT string_agg(column_name, ',' ORDER BY ordinal_position)"
                + " FROM information_schema.columns"
                + " WHERE table_name = 'denied_access_tokens'"))
                .as("no column for the account: a row says one token is dead,"
                        + " not who signed out")
                .isEqualTo("id,token_id,denied_at,expires_at");
        assertThat(one("SELECT data_type FROM information_schema.columns"
                + " WHERE table_name = 'denied_access_tokens'"
                + " AND column_name = 'expires_at'"))
                .as("an absolute instant, so instances in different zones agree")
                .isEqualTo("timestamp with time zone");
        assertThat(one("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'denied_access_tokens' AND column_name = 'token_id'"))
                .as("AccessTokenDenyList.MAX_TOKEN_ID_LENGTH is this number")
                .isEqualTo("64");
    }

    @Test
    void theTokenIdIsUniqueSoASecondLogoutCannotAddASecondRow() throws SQLException {
        flyway(null).migrate();

        exec(testUrl(), "INSERT INTO denied_access_tokens (token_id, denied_at, expires_at)"
                + " VALUES ('jti-1', now(), now() + INTERVAL '15 minutes')");

        assertThatThrownBy(() -> exec(testUrl(),
                "INSERT INTO denied_access_tokens (token_id, denied_at, expires_at)"
                        + " VALUES ('jti-1', now(), now() + INTERVAL '15 minutes')"))
                .as("which is why the store inserts with ON CONFLICT DO NOTHING")
                .isInstanceOf(SQLException.class);
        assertThat(one("SELECT count(*) FROM denied_access_tokens")).isEqualTo("1");
    }

    @Test
    void anEntryThatWouldOutliveNothingIsRefusedByTheCheck() throws SQLException {
        flyway(null).migrate();

        // The store never writes such a row — it returns early for a token
        // that has already expired — so this is the constraint standing
        // behind that decision rather than a case the application relies on.
        assertThatThrownBy(() -> exec(testUrl(),
                "INSERT INTO denied_access_tokens (token_id, denied_at, expires_at)"
                        + " VALUES ('jti-dead', now(), now() - INTERVAL '1 second')"))
                .isInstanceOf(SQLException.class);
        assertThat(one("SELECT count(*) FROM denied_access_tokens")).isEqualTo("0");
    }

    @Test
    void bothIndexesExistBecauseTheHotPathAndTheSweepReadDifferently()
            throws SQLException {
        flyway(null).migrate();

        // The unique constraint's own index serves the per-request lookup,
        // which is by token_id alone; (expires_at) serves the hourly sweep,
        // which crosses every row.
        assertThat(one("SELECT string_agg(indexname, ',' ORDER BY indexname)"
                + " FROM pg_indexes WHERE tablename = 'denied_access_tokens'"))
                .isEqualTo("denied_access_tokens_pkey,"
                        + "idx_denied_access_tokens_expires,"
                        + "uq_denied_access_tokens_token_id");
    }

    @Test
    void v12LeavesAnExistingV11DatabaseIntact() throws SQLException {
        flyway("11").migrate();
        exec(testUrl(), "INSERT INTO users (username, email, password_hash, display_name,"
                + " status) VALUES ('ada', 'ada@example.test', 'x', 'Ada', 'ACTIVE')");
        exec(testUrl(), "INSERT INTO rate_limit_hits (bucket, hit_at)"
                + " VALUES ('addr:10.0.0.1', now())");

        // Target V12, not "latest": the count below is a claim about this
        // migration's own step, and a V13 would otherwise turn it into a
        // claim about how many migrations exist.
        flyway("12").migrate();

        assertThat(one("SELECT count(*) FROM users WHERE username = 'ada'")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM rate_limit_hits")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM denied_access_tokens")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM flyway_schema_history WHERE success"))
                .as("V12 added itself and re-ran nothing").isEqualTo("12");
    }
}
