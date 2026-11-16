-- V17 — drop the invitation workflow's storage (scope A, PR-5).
--
-- THIS IS THE ONE STEP OF THE REMOVAL THAT `git revert` CANNOT UNDO.
-- Everything else in scope A is code. This deletes rows. It runs only after
-- scripts/export-invitations.sh has written the CSV, and the guard below
-- enforces that rather than trusting it.
--
-- WHAT GOES
--   invitations                          table, created by V1, extended by
--                                        V2 (email_status) and V7 (five
--                                        guest_code_* columns)
--   idx_invitations_email                V1
--   idx_invitations_status               V1
--   uq_invitations_one_pending_per_slot  V1, partial unique
--   idx_invitations_workflow_slot        V3
--   workflow_signers.invited_at          V1; only the removed invitation
--                                        coordinator ever set it
--   invitations_exported                 the export marker, once it has done
--                                        its job
--
-- The table's own constraints (primary key, uq_invitations_token,
-- ck_invitations_status, fk_invitations_workflow, ck_invitations_email_status
-- and the two guest_code checks) go with the table; naming them here would
-- only risk disagreeing with it.
--
-- WHAT STAYS, AND THIS IS THE POINT
--
--   audit_events is NOT TOUCHED. Not one row. The INVITATION_CREATED,
--   INVITATION_SENT, INVITATION_ACCEPTED, INVITATION_REVOKED and
--   INVITATION_EXPIRED rows there record invitations that really were sent
--   and accepted, and the product's dispute story rests on that trail being
--   append-only. Their enum values stay in the code, deprecated, and the
--   website still renders them as prose so an old document reads correctly.
--   This migration contains no DELETE and no TRUNCATE. Neither does any
--   other migration in this removal.
--
--   users is NOT TOUCHED either. Accounts with status PENDING_INVITE were
--   created as passwordless placeholders for invited addresses. They are
--   still referenced by workflow_signers rows through fk_signers_user, and
--   deleting them would take real signing history with them. They keep the
--   status name; see AccountStatus for what it means now.
--
-- ROLLBACK is scripts/db/V17_down.sql plus the exported CSV. Flyway does not
-- run it — rolling a migration back is a decision somebody makes, not
-- something a tool should do on its own. After restoring, `flyway repair`
-- clears the V17 row from flyway_schema_history.
--
-- WHY A TABLE AND NOT JUST THE COLUMNS: there is no partial removal worth
-- having. V16 marked everything deprecated and nothing has read or written
-- this table since commit 02eb12b, so a half-dropped shape would be an
-- orphan with a story attached, which is exactly what the audit flagged.

-- ── The guard ───────────────────────────────────────────────────────────
-- A NON-EMPTY table may only be dropped once the export has run. An empty
-- one needs no export and drops without the marker, which is what lets a
-- fresh database and every test database migrate straight through.
DO $$
DECLARE
    remaining bigint;
BEGIN
    SELECT count(*) INTO remaining FROM invitations;

    IF remaining > 0 AND NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_name = 'invitations_exported'
    ) THEN
        RAISE EXCEPTION
            'V17 refuses to drop % invitation row(s) that have not been '
            'exported. Run scripts/export-invitations.sh against this '
            'database first; it writes the CSV and the marker this check '
            'looks for. (An empty invitations table drops without one.)',
            remaining;
    END IF;

    IF remaining > 0 THEN
        RAISE NOTICE 'V17: dropping % exported invitation row(s).', remaining;
    END IF;
END $$;

-- ── The drop ────────────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_invitations_workflow_slot;
DROP INDEX IF EXISTS uq_invitations_one_pending_per_slot;
DROP INDEX IF EXISTS idx_invitations_status;
DROP INDEX IF EXISTS idx_invitations_email;

DROP TABLE IF EXISTS invitations;

ALTER TABLE workflow_signers DROP COLUMN IF EXISTS invited_at;

-- The marker has served its purpose; leaving it would be the orphan this
-- migration exists to remove. The export itself lives in the retention
-- store, and its manifest records the row count and the CSV's SHA-256.
DROP TABLE IF EXISTS invitations_exported;
