-- V10 — one-time account tokens (security Phase D: website accounts).
--
-- New tables only. Nothing existing is dropped, altered or rewritten.
--
-- Two kinds of single-use link token, both handled exactly like the
-- invitation tokens (V2) and launch codes (V8): the raw value is 256 bits of
-- SecureRandom, delivered once by e-mail and NEVER stored — only its SHA-256
-- hex digest is. A token is consumed by an UPDATE that checks
-- `consumed_at IS NULL`, so two concurrent uses cannot both win.
--
--   email_verification_tokens  proves control of an address.   24 h.
--   password_reset_tokens      authorises setting a new password. 30 min.
--
-- `email` is recorded on a verification token because an address can change
-- between issuing and clicking: the token proves the address it was sent to,
-- not whatever the account holds at the time it is used.

CREATE TABLE email_verification_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    token_hash  CHAR(64)     NOT NULL,
    username    VARCHAR(100) NOT NULL REFERENCES users (username)
                             ON DELETE CASCADE ON UPDATE CASCADE,
    email       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ      NULL,
    CONSTRAINT uq_email_verification_hash UNIQUE (token_hash),
    CONSTRAINT ck_email_verification_expiry CHECK (expires_at > created_at)
);

-- "Does this account have a live token?" — the resend throttle and the
-- invalidate-older-tokens step both need it.
CREATE INDEX idx_email_verification_username ON email_verification_tokens (username);
-- Expiry sweep.
CREATE INDEX idx_email_verification_expires ON email_verification_tokens (expires_at);

CREATE TABLE password_reset_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    token_hash  CHAR(64)     NOT NULL,
    username    VARCHAR(100) NOT NULL REFERENCES users (username)
                             ON DELETE CASCADE ON UPDATE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ      NULL,
    CONSTRAINT uq_password_reset_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_password_reset_username ON password_reset_tokens (username);
CREATE INDEX idx_password_reset_expires ON password_reset_tokens (expires_at);
