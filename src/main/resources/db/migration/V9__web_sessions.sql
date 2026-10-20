-- V9 — server-side website sessions (security Phase C).
--
-- New tables only. Nothing existing is dropped, altered or rewritten: the
-- website login is a new, separate security domain and shares nothing with
-- the desktop's bearer tokens (V6 refresh_tokens) or the launch codes (V8).
--
-- This is the schema Spring Session JDBC expects, taken from
-- spring-session-jdbc's own schema-postgresql.sql so its queries match.
-- PostgreSQL folds the unquoted identifiers to lower case, which is also
-- what Spring Session's unquoted statements resolve to.
--
-- What lives here: the session id, its timestamps and its serialized
-- attributes (among them the authenticated principal). NO password, no
-- password hash, no bearer token and no CSRF token is stored in a session.
-- A row is removed when the session is invalidated (logout) or expires;
-- spring.session.jdbc.cleanup-cron also deletes rows past expiry_time.
CREATE TABLE spring_session (
    primary_id            CHAR(36)     NOT NULL,
    session_id            CHAR(36)     NOT NULL,
    creation_time         BIGINT       NOT NULL,
    last_access_time      BIGINT       NOT NULL,
    max_inactive_interval INTEGER      NOT NULL,
    expiry_time           BIGINT       NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

-- Lookup by the id carried in the cookie.
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
-- Expiry sweep.
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
-- "Log out everywhere" / revoke-all-sessions-for-an-account.
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk
        PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk
        FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id)
        ON DELETE CASCADE
);
