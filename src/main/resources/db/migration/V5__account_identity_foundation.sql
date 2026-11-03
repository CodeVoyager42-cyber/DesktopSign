-- V5 — account identity foundation (security Phase 1).
--
-- Additive only: no column is dropped or rewritten, existing rows keep their
-- data. The new fields are the schema the next phases build on:
--
--   role                 explicit permissions; every existing account is USER.
--                        ADMIN is granted only deliberately (no code path
--                        grants it yet).
--   email_verified_at    when the account proved control of its email address.
--                        NULL for existing rows: nothing recorded proves it.
--                        Invitation-bound registration sets it from now on.
--   password_changed_at  backfilled with updated_at for accounts that have a
--                        password (best available approximation).
--   failed_login_count,  login throttling / lockout state (logic: Phase 2).
--   locked_until
--
-- Usernames become case-insensitive: 'alice' and 'Alice' can no longer
-- coexist. If such rows already exist the migration stops (and, PostgreSQL
-- DDL being transactional, changes nothing) with the conflicting names, so
-- they can be renamed first.
DO $$
DECLARE
    conflicts TEXT;
BEGIN
    SELECT string_agg(name, ', ')
      INTO conflicts
      FROM (SELECT lower(username) AS name
              FROM users
             GROUP BY lower(username)
            HAVING count(*) > 1) duplicates;
    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION 'V5 cannot make usernames case-insensitive: these usernames exist with different letter case: %. Rename the duplicates, then restart the server.', conflicts;
    END IF;
END $$;

ALTER TABLE users
    ADD COLUMN role                VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD COLUMN email_verified_at   TIMESTAMPTZ     NULL,
    ADD COLUMN password_changed_at TIMESTAMPTZ     NULL,
    ADD COLUMN failed_login_count  INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until        TIMESTAMPTZ     NULL,
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    ADD CONSTRAINT ck_users_failed_login_count CHECK (failed_login_count >= 0);

UPDATE users
   SET password_changed_at = updated_at
 WHERE password_hash IS NOT NULL;

CREATE UNIQUE INDEX uq_users_username_ci ON users (lower(username));
