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
 * V5 against a real PostgreSQL: a fresh install, and an existing V4
 * database that already holds accounts. Uses its own throwaway database
 * ({@value #DB}), created and dropped by this test only. Skipped when
 * PostgreSQL is not reachable.
 */
@EnabledIf(value = "com.example.signer.server.db.AccountIdentityMigrationLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_URL/SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class AccountIdentityMigrationLiveTests {

    private static final String DB = "signflow_v5_migration_test";

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String adminUrl() {
        return env("SIGNER_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    }

    private static String user() {
        return env("SIGNER_DB_USERNAME", "postgres");
    }

    private static String password() {
        return env("SIGNER_DB_PASSWORD", "");
    }

    private static String testUrl() {
        String admin = adminUrl();
        return admin.substring(0, admin.lastIndexOf('/') + 1) + DB;
    }

    public static boolean postgresReachable() {
        try (Connection c = DriverManager.getConnection(adminUrl(), user(), password())) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void admin(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(adminUrl(), user(), password());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        admin("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        admin("CREATE DATABASE " + DB);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        admin("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(testUrl(), user(), password())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    private void sql(String statement) throws SQLException {
        try (Connection c = DriverManager.getConnection(testUrl(), user(), password());
             Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private String one(String query) throws SQLException {
        try (Connection c = DriverManager.getConnection(testUrl(), user(), password());
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(query)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    private void insertV4User(String username, String hash, String status) throws SQLException {
        sql("INSERT INTO users (username, email, password_hash, display_name, status, created_at, updated_at)"
                + " VALUES ('" + username + "', '" + username + "@example.test', "
                + (hash == null ? "NULL" : "'" + hash + "'") + ", '" + username + "', '" + status + "',"
                + " TIMESTAMPTZ '2026-01-02 03:04:05+00', TIMESTAMPTZ '2026-02-03 04:05:06+00')");
    }

    @Test
    void freshInstallReachesV5WithAllColumns() throws SQLException {
        flyway(null).migrate();
        assertThat(one("SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("5");
        assertThat(one("SELECT count(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name IN"
                + " ('role','email_verified_at','password_changed_at','failed_login_count','locked_until')"))
                .isEqualTo("5");
        assertThat(one("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_users_username_ci'")).isEqualTo("1");
    }

    @Test
    void existingV4AccountsKeepTheirDataAndGetSafeDefaults() throws SQLException {
        flyway("4").migrate();
        String hash = "$2a$10$abcdefghijklmnopqrstuuJ3Lr0V3cXlJ4oVZ7Q0bZz7xWm1XcQ6";
        insertV4User("alice", hash, "ACTIVE");
        insertV4User("pending", null, "PENDING_INVITE");
        insertV4User("off", hash, "DISABLED");

        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM users")).isEqualTo("3");
        assertThat(one("SELECT password_hash FROM users WHERE username = 'alice'")).isEqualTo(hash);
        assertThat(one("SELECT status FROM users WHERE username = 'pending'")).isEqualTo("PENDING_INVITE");
        assertThat(one("SELECT count(*) FROM users WHERE role = 'USER'")).isEqualTo("3");
        assertThat(one("SELECT count(*) FROM users WHERE failed_login_count = 0 AND locked_until IS NULL")).isEqualTo("3");
        // Nothing proves existing addresses, so none is marked verified.
        assertThat(one("SELECT count(*) FROM users WHERE email_verified_at IS NULL")).isEqualTo("3");
        // Backfill: accounts with a password take updated_at; placeholders stay NULL.
        assertThat(one("SELECT count(*) FROM users WHERE password_changed_at = updated_at"
                + " AND username IN ('alice','off')")).isEqualTo("2");
        assertThat(one("SELECT password_changed_at FROM users WHERE username = 'pending'")).isNull();
    }

    @Test
    void constraintsRejectBadRolesNegativeCountsAndCaseOnlyDuplicates() throws SQLException {
        flyway(null).migrate();
        insertV4User("alice", null, "PENDING_INVITE");
        assertThatThrownBy(() -> sql("UPDATE users SET role = 'ROOT' WHERE username = 'alice'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_users_role");
        assertThatThrownBy(() -> sql("UPDATE users SET failed_login_count = -1 WHERE username = 'alice'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_users_failed_login_count");
        assertThatThrownBy(() -> sql("INSERT INTO users (username, status, created_at, updated_at)"
                + " VALUES ('Alice', 'PENDING_INVITE', now(), now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uq_users_username_ci");
    }

    @Test
    void caseOnlyDuplicatesStopV5WithoutChangingAnything() throws SQLException {
        flyway("4").migrate();
        insertV4User("bob", null, "PENDING_INVITE");
        insertV4User("Bob", null, "PENDING_INVITE");

        assertThatThrownBy(() -> flyway(null).migrate())
                .hasStackTraceContaining("V5 cannot make usernames case-insensitive")
                .hasStackTraceContaining("bob");

        // Transactional DDL: no V5 column was added, both rows are intact.
        assertThat(one("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = 'users' AND column_name = 'role'")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM users")).isEqualTo("2");

        // After renaming the duplicate, V5 applies.
        sql("UPDATE users SET username = 'bob2', email = 'bob2@example.test' WHERE username = 'Bob'");
        flyway(null).repair();
        flyway(null).migrate();
        assertThat(one("SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("5");
    }
}
