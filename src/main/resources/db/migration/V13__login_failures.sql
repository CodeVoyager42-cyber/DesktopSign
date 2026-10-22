-- V13 — failed sign-ins per account AND client address (Phase 2 risk L3).
--
-- New table only. Nothing existing is dropped, altered or rewritten; in
-- particular users.failed_login_count and users.locked_until (V5) stay, and
-- still work the way they did.
--
-- The problem this solves: the lock lived only on the account, so five wrong
-- passwords from anywhere locked the account for everyone, the real holder
-- included. Usernames are predictable (an e-mail local part), so anyone could
-- keep a known account locked out indefinitely for the price of five requests
-- every fifteen minutes — well under the per-address rate limit.
--
-- A failure is now counted twice: here, against the pair that caused it, and
-- on the account row as before. Five from one address lock that address out
-- of that account; the holder, arriving from their own address, is untouched.
-- The account-wide lock remains as a backstop against guessing spread over
-- many addresses, at a much higher threshold.
--
-- `client_address` holds what the servlet container reports as the remote
-- address, the same value the per-address rate limit counts, and never a
-- forwarded header — any client can send one of those. It is stored as it is,
-- like `rate_limit_hits.bucket` already does; the privacy note is in the
-- phase document, and the sweep below bounds how long it is kept.
CREATE TABLE login_failures (
    id               BIGSERIAL    PRIMARY KEY,
    username         VARCHAR(100) NOT NULL REFERENCES users (username)
                                  ON DELETE CASCADE ON UPDATE CASCADE,
    client_address   VARCHAR(64)  NOT NULL,
    failed_count     INTEGER      NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ      NULL,
    first_failure_at TIMESTAMPTZ  NOT NULL,
    last_failure_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_login_failures_account_address UNIQUE (username, client_address)
);

-- The UNIQUE constraint's index is the one the sign-in path uses: every read
-- and every write is for one exact (username, client_address) pair.

-- The hourly sweep, which crosses every pair.
CREATE INDEX idx_login_failures_last_failure ON login_failures (last_failure_at);

-- ON DELETE CASCADE above: a deleted account takes its failure rows with it,
-- like its refresh tokens (V6) and its one-time link tokens (V10). There is
-- deliberately no index on client_address alone — nothing asks "what has this
-- address been attacking", and an index is an invitation to start.
