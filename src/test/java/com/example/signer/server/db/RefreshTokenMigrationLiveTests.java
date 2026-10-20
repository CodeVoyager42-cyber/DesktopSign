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
 * V6 against a real PostgreSQL, on top of an existing V5 database with
 * accounts. Own throwaway database ({@value #DB}). Skipped when PostgreSQL
 * is not reachable.
 */
@EnabledIf(value = "com.example.signer.server.db.AccountIdentityMigrationLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_URL/SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class RefreshTokenMigrationLiveTests {

    private static final String DB = "signflow_v6_migration_test";

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
        var config = Flyway.configure().dataSource(testUrl(), env("SIGNER_DB_USERNAME", "postgres"),
                env("SIGNER_DB_PASSWORD", "")).locations("classpath:db/migration");
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
    void v6AddsTheTableAndKeepsExistingAccounts() throws SQLException {
        flyway("5").migrate();
        exec(testUrl(), "INSERT INTO users (username, email, status, created_at, updated_at)"
                + " VALUES ('alice', 'alice@example.test', 'PENDING_INVITE', now(), now())");

        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success"))
                .isEqualTo("1");
        assertThat(one("SELECT count(*) FROM users")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM refresh_tokens")).isEqualTo("0");
    }

    @Test
    void constraintsProtectTheTable() throws SQLException {
        flyway(null).migrate();
        exec(testUrl(), "INSERT INTO users (username, email, status, created_at, updated_at)"
                + " VALUES ('alice', 'alice@example.test', 'PENDING_INVITE', now(), now())");
        String hash = "a".repeat(64);
        String insert = "INSERT INTO refresh_tokens (token_hash, username, family_id, issued_at, expires_at)"
                + " VALUES ('%s', '%s', gen_random_uuid(), now(), now() + interval '1 hour')";
        exec(testUrl(), String.format(insert, hash, "alice"));

        assertThatThrownBy(() -> exec(testUrl(), String.format(insert, hash, "alice")))
                .hasMessageContaining("uq_refresh_tokens_hash");
        assertThatThrownBy(() -> exec(testUrl(), String.format(insert, "b".repeat(64), "nobody")))
                .isInstanceOf(SQLException.class);   // unknown account (foreign key)
        assertThatThrownBy(() -> exec(testUrl(), "INSERT INTO refresh_tokens (token_hash, username,"
                + " family_id, issued_at, expires_at) VALUES ('" + "c".repeat(64) + "', 'alice',"
                + " gen_random_uuid(), now(), now() - interval '1 hour')"))
                .hasMessageContaining("ck_refresh_tokens_expiry");

        // Deleting the account deletes its sessions.
        exec(testUrl(), "DELETE FROM users WHERE username = 'alice'");
        assertThat(one("SELECT count(*) FROM refresh_tokens")).isEqualTo("0");
    }
}
