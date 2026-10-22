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

/**
 * V11 (shared rate-limit counters, security Phase D1 risk D3) against a real
 * PostgreSQL, on a fresh database and on top of an existing V10 one that
 * already holds data. Own throwaway database ({@value #DB}). Skipped when
 * PostgreSQL is not reachable.
 *
 * <p>Same point as the V9 and V10 tests: V11 is <b>additive</b>. It creates
 * one table and two indexes and touches nothing that was already there — in
 * particular not the accounts and tokens a running deployment would be
 * holding when it is applied.
 */
@EnabledIf(value = "com.example.signer.server.db.AccountIdentityMigrationLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_URL/SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class RateLimitMigrationLiveTests {

    private static final String DB = "signflow_v11_migration_test";

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
    void v11AppliesOnAFreshDatabaseAndCreatesTheCounterTable() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM flyway_schema_history"
                + " WHERE version = '11' AND success")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM rate_limit_hits")).isEqualTo("0");
    }

    @Test
    void theColumnsAreTheThreeTheStoreWritesAndNothingElse() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT string_agg(column_name, ',' ORDER BY ordinal_position)"
                + " FROM information_schema.columns"
                + " WHERE table_name = 'rate_limit_hits'"))
                .as("no column for anything secret: a bucket is an address, never a token")
                .isEqualTo("id,bucket,hit_at");
        assertThat(one("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'rate_limit_hits' AND column_name = 'hit_at'"))
                .isEqualTo("NO");
        assertThat(one("SELECT data_type FROM information_schema.columns"
                + " WHERE table_name = 'rate_limit_hits' AND column_name = 'hit_at'"))
                .as("an absolute instant, so instances in different zones agree")
                .isEqualTo("timestamp with time zone");
    }

    @Test
    void bothIndexesExistBecauseTheHotPathAndTheSweepReadDifferently()
            throws SQLException {
        flyway(null).migrate();

        // (bucket, hit_at) serves counting and pruning one bucket; (hit_at)
        // serves the sweep, which crosses every bucket.
        assertThat(one("SELECT string_agg(indexname, ',' ORDER BY indexname)"
                + " FROM pg_indexes WHERE tablename = 'rate_limit_hits'"))
                .isEqualTo("idx_rate_limit_bucket_hit_at,idx_rate_limit_hit_at,"
                        + "rate_limit_hits_pkey");
    }

    @Test
    void theAdvisoryLockTheStoreReliesOnWorksOnThisTable() throws SQLException {
        flyway(null).migrate();

        // The store serialises a bucket with pg_advisory_xact_lock(hashtext(…)),
        // so the cast it uses has to resolve on this server, not just on the
        // one it was written against. An unresolved cast fails the statement,
        // which fails this test with the server's own message — the sentinel
        // only proves the statement ran.
        assertThat(one("SELECT 'locked' FROM"
                + " (SELECT pg_advisory_xact_lock(hashtext('addr:10.0.0.1')::bigint)) t"))
                .isEqualTo("locked");
    }

    @Test
    void v11LeavesAnExistingV10DatabaseIntact() throws SQLException {
        flyway("10").migrate();
        exec(testUrl(), "INSERT INTO users (username, email, password_hash, display_name,"
                + " status) VALUES ('ada', 'ada@example.test', 'x', 'Ada', 'ACTIVE')");
        exec(testUrl(), "INSERT INTO password_reset_tokens (token_hash, username,"
                + " created_at, expires_at) VALUES ('" + "a".repeat(64) + "', 'ada',"
                + " now(), now() + INTERVAL '30 minutes')");

        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM users WHERE username = 'ada'")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM password_reset_tokens")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM rate_limit_hits")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM flyway_schema_history WHERE success"))
                .as("V11 added itself and re-ran nothing").isEqualTo("11");
    }
}
