-- V6 — persistent refresh tokens (security Phase 2).
--
-- New table only; nothing existing is changed. Before V6 refresh tokens
-- lived in server memory, so a restart signed every user out.
--
-- Only the SHA-256 hash of a token is stored: the token itself is shown to
-- the client once and never written anywhere. A "family" is one sign-in:
-- each refresh replaces the token with a new one in the same family; using
-- a replaced token again revokes the whole family (likely theft).
CREATE TABLE refresh_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    token_hash  CHAR(64)     NOT NULL,
    username    VARCHAR(100) NOT NULL REFERENCES users (username) ON DELETE CASCADE ON UPDATE CASCADE,
    family_id   UUID         NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    rotated_at  TIMESTAMPTZ      NULL,
    revoked_at  TIMESTAMPTZ      NULL,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX idx_refresh_tokens_family   ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_username ON refresh_tokens (username);
CREATE INDEX idx_refresh_tokens_expires  ON refresh_tokens (expires_at);
