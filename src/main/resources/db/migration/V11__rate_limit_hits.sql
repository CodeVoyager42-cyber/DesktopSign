-- V11 — shared rate-limit counters (security Phase D1 risk D3, and C6).
--
-- New table only. Nothing existing is dropped, altered or rewritten.
--
-- Every limiter in the application — per client address, per sign-up address,
-- per e-mail recipient, per account whose one-time token is being guessed —
-- counted in a map on the heap until now. That made each cap per server
-- instance and reset it on every restart: behind two instances the effective
-- cap was double, and a restart handed an attacker a fresh budget.
--
-- One row per counted attempt, deleted once it leaves its window. The window
-- is a genuine sliding one: the count is of the rows inside it, and
-- Retry-After is computed from the OLDEST row still in it, which is why the
-- hit times are kept rather than a single counter per bucket. A fixed-window
-- counter would be smaller and would answer a different question.
--
-- `bucket` is the limiter's key, namespaced by the limiter that owns it
-- (`addr:`, `signup:`, `mail:`, `attempt:`) so two limiters cannot collide
-- in here. It holds a client address or an e-mail address — never a
-- password, a token or a token hash.
--
-- Writes are serialised per bucket by pg_advisory_xact_lock(hashtext(bucket))
-- rather than by a row lock: there is no bucket row to lock, and counting
-- then inserting without it would let concurrent requests all read the same
-- count and every one of them be admitted.

CREATE TABLE rate_limit_hits (
    id     BIGSERIAL    PRIMARY KEY,
    bucket VARCHAR(320) NOT NULL,
    hit_at TIMESTAMPTZ  NOT NULL
);

-- The only read: count the rows of one bucket inside its window, and find
-- the oldest of them. Also the only delete on the hot path (pruning a
-- bucket as it is used), so both columns in this order.
CREATE INDEX idx_rate_limit_bucket_hit_at ON rate_limit_hits (bucket, hit_at);
-- The hourly sweep, which deletes by age across every bucket: a bucket that
-- is never used again is not pruned by its own next request.
CREATE INDEX idx_rate_limit_hit_at ON rate_limit_hits (hit_at);
