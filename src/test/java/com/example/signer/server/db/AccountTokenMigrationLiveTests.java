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
 * V10 (account tokens, security Phase D) against a real PostgreSQL, on a
 * fresh database and on top of an existing V9 one that already holds data.
 * Own throwaway database ({@value #DB}). Skipped when PostgreSQL is not
 * reachable.
 *
 * <p>The point of these tests is the same as the V9 ones: V10 is
 * <b>additive</b>. It must create its two token tables, with the constraints
 * the service relies on, and touch nothing that was already there.
 */
@EnabledIf(value = "com.example.signer.server.db.AccountIdentityMigrationLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_URL/SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class AccountTokenMigrationLiveTests {

    private static final String DB = "signflow_v10_migration_test";

    /** A stand-in digest: the column is CHAR(64), the width of SHA-256 hex. */
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

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

    /** An account for the tokens to reference. */
    private static void insertUser(String username) throws SQLException {
        exec(testUrl(), "INSERT INTO users (username, email, password_hash, display_name,"
                + " status) VALUES ('" + username + "', '" + username + "@example.test',"
                + " 'x', 'Holder', 'ACTIVE')");
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
    void v10AppliesOnAFreshDatabaseAndCreatesBothTokenTables() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM flyway_schema_history"
                + " WHERE version = '10' AND success")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM email_verification_tokens")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM password_reset_tokens")).isEqualTo("0");
    }

    @Test
    void bothTablesHoldAHashAndNeverTheValueThatWasEmailed() throws SQLException {
        flyway(null).migrate();

        // No column for a raw token: only its digest is stored, and the width
        // is SHA-256 hex.
        assertThat(one("SELECT string_agg(column_name, ',' ORDER BY column_name)"
                + " FROM information_schema.columns"
                + " WHERE table_name = 'email_verification_tokens'"))
                .isEqualTo("consumed_at,created_at,email,expires_at,id,token_hash,username");
        assertThat(one("SELECT string_agg(column_name, ',' ORDER BY column_name)"
                + " FROM information_schema.columns"
                + " WHERE table_name = 'password_reset_tokens'"))
                .isEqualTo("consumed_at,created_at,expires_at,id,token_hash,username");
        assertThat(one("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'password_reset_tokens'"
                + " AND column_name = 'token_hash'")).isEqualTo("64");

        // The hash is looked up on every click, so it must be unique and
        // indexed rather than merely present: two tokens cannot share one.
        assertThat(one("SELECT indexdef FROM pg_indexes WHERE indexname ="
                + " 'uq_email_verification_hash'")).contains("UNIQUE");
        assertThat(one("SELECT indexdef FROM pg_indexes WHERE indexname ="
                + " 'uq_password_reset_hash'")).contains("UNIQUE");
        insertUser("holder");
        exec(testUrl(), "INSERT INTO password_reset_tokens (token_hash, username,"
                + " created_at, expires_at) VALUES ('" + HASH + "', 'holder',"
                + " now(), now() + INTERVAL '30 minutes')");
        assertThatThrownBy(() -> exec(testUrl(), "INSERT INTO password_reset_tokens"
                + " (token_hash, username, created_at, expires_at) VALUES ('" + HASH
                + "', 'holder', now(), now() + INTERVAL '30 minutes')"))
                .as("the same digest must not be storable twice")
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aLinkThatWouldExpireBeforeItWasIssuedIsRefused() throws SQLException {
        flyway(null).migrate();
        insertUser("holder");

        assertThatThrownBy(() -> exec(testUrl(), "INSERT INTO email_verification_tokens"
                + " (token_hash, username, email, created_at, expires_at) VALUES ('" + HASH
                + "', 'holder', 'holder@example.test', now(), now() - INTERVAL '1 hour')"))
                .as("ck_email_verification_expiry must refuse a dead-on-arrival link")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(testUrl(), "INSERT INTO password_reset_tokens"
                + " (token_hash, username, created_at, expires_at) VALUES ('" + OTHER_HASH
                + "', 'holder', now(), now())"))
                .as("ck_password_reset_expiry must refuse a zero-length life")
                .isInstanceOf(SQLException.class);
    }

    @Test
    void tokensAreDeletedWithTheirAccount() throws SQLException {
        flyway(null).migrate();
        insertUser("holder");
        exec(testUrl(), "INSERT INTO email_verification_tokens (token_hash, username,"
                + " email, created_at, expires_at) VALUES ('" + HASH + "', 'holder',"
                + " 'holder@example.test', now(), now() + INTERVAL '24 hours')");
        exec(testUrl(), "INSERT INTO password_reset_tokens (token_hash, username,"
                + " created_at, expires_at) VALUES ('" + OTHER_HASH + "', 'holder',"
                + " now(), now() + INTERVAL '30 minutes')");

        exec(testUrl(), "DELETE FROM users WHERE username = 'holder'");

        assertThat(one("SELECT count(*) FROM email_verification_tokens"))
                .as("a removed account must not leave usable links behind").isEqualTo("0");
        assertThat(one("SELECT count(*) FROM password_reset_tokens")).isEqualTo("0");
    }

    @Test
    void v10IsAdditiveAndLeavesAnExistingV9DatabaseIntact() throws SQLException {
        flyway("9").migrate();
        exec(testUrl(), "INSERT INTO users (username, email, password_hash, display_name,"
                + " status) VALUES ('keepme', 'keepme@example.test', 'x', 'Keep Me', 'ACTIVE')");
        // A live website session: V10 must not disturb Phase C's tables either.
        exec(testUrl(), "INSERT INTO spring_session (primary_id, session_id,"
                + " creation_time, last_access_time, max_inactive_interval, expiry_time)"
                + " VALUES ('33333333-3333-3333-3333-333333333333',"
                + " '44444444-4444-4444-4444-444444444444', 0, 0, 1800, 1800000)");
        String before = one("SELECT string_agg(table_name, ',' ORDER BY table_name)"
                + " FROM information_schema.tables WHERE table_schema = 'public'");

        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM users WHERE username = 'keepme'"))
                .as("V10 must not touch existing rows").isEqualTo("1");
        assertThat(one("SELECT email FROM users WHERE username = 'keepme'"))
                .isEqualTo("keepme@example.test");
        assertThat(one("SELECT count(*) FROM spring_session WHERE session_id ="
                + " '44444444-4444-4444-4444-444444444444'"))
                .as("an existing website session survives the migration").isEqualTo("1");
        String after = one("SELECT string_agg(table_name, ',' ORDER BY table_name)"
                + " FROM information_schema.tables WHERE table_schema = 'public'");
        // Only additions: every pre-V10 table is still there.
        for (String table : before.split(",")) {
            assertThat(after).contains(table);
        }
        assertThat(after).contains("email_verification_tokens", "password_reset_tokens");
    }
}
