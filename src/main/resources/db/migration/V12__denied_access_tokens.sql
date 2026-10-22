-- V12 — the access-token deny-list (security Phase 2 risk L4).
--
-- New table only. Nothing existing is dropped, altered or rewritten.
--
-- An access token is a signed JWT: nothing is stored for it, so signing out
-- could revoke the refresh session and nothing else, and the token kept
-- working for the rest of its 15 minutes. This table is what signing out
-- writes instead — the token's `jti`, until the token would have expired
-- anyway.
--
-- `token_id` is the `jti` claim, stored AS IT IS and not hashed, which is
-- the one place this table differs from `refresh_tokens` (V6) and the V10
-- link tokens. Those hold hashes because the value is a bearer credential:
-- whoever reads the row could use it. A `jti` is an identifier inside a
-- token that is signed with a secret this table does not contain, so a row
-- here grants nothing, and an identifier that can be read back is one an
-- operator can match against a log line.
--
-- There is no `username` column. A row says "this one token is dead" and
-- deliberately not "who signed out, and when from where" — the sweep needs
-- only the expiry, and the per-request check needs only the id.
CREATE TABLE denied_access_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    token_id   VARCHAR(64) NOT NULL,
    denied_at  TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_denied_access_tokens_token_id UNIQUE (token_id),
    CONSTRAINT ck_denied_access_tokens_expiry CHECK (expires_at > denied_at)
);

-- The CHECK can never fire: a token that has already expired is refused by
-- the decoder anyway, so the store does not write a row for one.

-- The UNIQUE constraint's index serves the per-request lookup, which is by
-- token_id alone. This one serves the hourly sweep, which crosses every row.
CREATE INDEX idx_denied_access_tokens_expires ON denied_access_tokens (expires_at);
