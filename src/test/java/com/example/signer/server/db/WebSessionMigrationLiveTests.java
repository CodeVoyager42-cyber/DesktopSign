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
 * V9 (website sessions, security Phase C) against a real PostgreSQL, applied
 * on top of an existing V8 database that already holds data. Own throwaway
 * database ({@value #DB}). Skipped when PostgreSQL is not reachable.
 *
 * <p>The point of these tests is that V9 is <b>additive</b>: it must create
 * its two tables and touch nothing that was already there.
 */
@EnabledIf(value = "com.example.signer.server.db.AccountIdentityMigrationLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_URL/SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class WebSessionMigrationLiveTests {

    private static final String DB = "signflow_v9_migration_test";

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
    void v9AppliesOnAFreshDatabaseAndCreatesBothSessionTables() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM flyway_schema_history"
                + " WHERE version = '9' AND success")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM spring_session")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM spring_session_attributes")).isEqualTo("0");
    }

    @Test
    void theSchemaIsTheOneSpringSessionJdbcQueries() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT string_agg(column_name, ',' ORDER BY column_name)"
                + " FROM information_schema.columns"
                + " WHERE table_name = 'spring_session'"))
                .isEqualTo("creation_time,expiry_time,last_access_time,"
                        + "max_inactive_interval,primary_id,principal_name,session_id");
        assertThat(one("SELECT string_agg(column_name, ',' ORDER BY column_name)"
                + " FROM information_schema.columns"
                + " WHERE table_name = 'spring_session_attributes'"))
                .isEqualTo("attribute_bytes,attribute_name,session_primary_id");
        // The session id is looked up on every request, so it must be unique
        // and indexed, not merely present.
        assertThat(one("SELECT count(*) FROM pg_indexes WHERE tablename ="
                + " 'spring_session' AND indexname = 'spring_session_ix1'"))
                .isEqualTo("1");
        assertThat(one("SELECT indexdef FROM pg_indexes WHERE indexname ="
                + " 'spring_session_ix1'")).contains("UNIQUE");
    }

    @Test
    void attributesAreDeletedWithTheirSession() throws SQLException {
        flyway(null).migrate();
        exec(testUrl(), "INSERT INTO spring_session (primary_id, session_id,"
                + " creation_time, last_access_time, max_inactive_interval, expiry_time)"
                + " VALUES ('11111111-1111-1111-1111-111111111111',"
                + " '22222222-2222-2222-2222-222222222222', 0, 0, 1800, 1800000)");
        exec(testUrl(), "INSERT INTO spring_session_attributes (session_primary_id,"
                + " attribute_name, attribute_bytes)"
                + " VALUES ('11111111-1111-1111-1111-111111111111', 'x', '\\x00')");

        exec(testUrl(), "DELETE FROM spring_session WHERE primary_id ="
                + " '11111111-1111-1111-1111-111111111111'");

        assertThat(one("SELECT count(*) FROM spring_session_attributes"))
                .as("a sign-out must not leave orphaned attribute rows")
                .isEqualTo("0");
    }

    @Test
    void v9IsAdditiveAndLeavesAnExistingV8DatabaseIntact() throws SQLException {
        flyway("8").migrate();
        exec(testUrl(), "INSERT INTO users (username, email, password_hash, display_name,"
                + " status) VALUES ('keepme', 'keepme@example.test', 'x', 'Keep Me', 'ACTIVE')");
        String before = one("SELECT string_agg(table_name, ',' ORDER BY table_name)"
                + " FROM information_schema.tables WHERE table_schema = 'public'");

        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM users WHERE username = 'keepme'"))
                .as("V9 must not touch existing rows").isEqualTo("1");
        assertThat(one("SELECT email FROM users WHERE username = 'keepme'"))
                .isEqualTo("keepme@example.test");
        String after = one("SELECT string_agg(table_name, ',' ORDER BY table_name)"
                + " FROM information_schema.tables WHERE table_schema = 'public'");
        // Only additions: every pre-V9 table is still there.
        for (String table : before.split(",")) {
            assertThat(after).contains(table);
        }
        assertThat(after).contains("spring_session", "spring_session_attributes");
    }
}
