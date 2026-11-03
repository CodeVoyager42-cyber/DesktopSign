-- V14 — the signing-invitation feature is gone, and a document has exactly
-- one signer: the account that uploaded it.
--
-- SignFlow no longer asks anybody else to sign anything. There are no
-- invitations, no guest sessions, no signing order to negotiate and no turn
-- to claim, so everything those features stored is dropped here.
--
-- DESTRUCTIVE. It removes the `invitations` table and the columns listed
-- below; on a deployment that used invitations, that history is not
-- recoverable afterwards. Take a backup first.
--
-- What it deliberately does NOT do: delete workflows. A workflow created
-- before this change may still have more than one signer row, and those
-- rows carry real, cryptographically verifiable signatures. Deleting them
-- to make the schema tidy would destroy signed documents. They stay, and
-- the application refuses to open them with a message saying why (see
-- SigningWorkflowAggregate.rehydrate and RemoteWorkflowService); their PDFs
-- remain on disk under {storage}/workflows/{id}/revisions/.
--
-- To find them before migrating:
--   SELECT w.workflow_id, count(*) AS signers
--     FROM workflows w JOIN workflow_signers s ON s.workflow_id = w.id
--    GROUP BY w.workflow_id HAVING count(*) > 1;

-- ── 1. invitations ───────────────────────────────────────────────────────
-- Carries the V2 delivery-status column and the V7 guest-code columns with
-- it; both only ever existed for invitations.
DROP TABLE IF EXISTS invitations;

-- ── 2. the signing policy and the turn claim ─────────────────────────────
-- One signer means no order to choose and no single turn to hand around.
ALTER TABLE workflows
    DROP CONSTRAINT IF EXISTS ck_workflows_policy,
    DROP CONSTRAINT IF EXISTS fk_workflows_claimed_turn;

ALTER TABLE workflows
    DROP COLUMN IF EXISTS policy,
    DROP COLUMN IF EXISTS claimed_turn_username,
    DROP COLUMN IF EXISTS claimed_turn_at;

-- ── 3. the per-slot invitation timestamp ─────────────────────────────────
ALTER TABLE workflow_signers
    DROP COLUMN IF EXISTS invited_at;

-- ── 4. audit rows of removed events ──────────────────────────────────────
-- The trail is append-only, so rows are not rewritten lightly. These two
-- are different in kind:
--
--   * CERTIFICATES_PENDING only ever meant "a slot still needs a
--     certificate". It is the same fact under a new name, so it is renamed
--     rather than deleted and the trail keeps its line.
--   * the rest record invitations, registrations through an invitation, and
--     turns being claimed and released. Those events cannot happen any more
--     and AuditEventType no longer has names for them, so they are removed.
--     (JpaWorkflowStore also skips an unknown event type rather than
--     failing, so a database that has not run this migration still opens.)
UPDATE audit_events SET event_type = 'CERTIFICATE_PENDING'
 WHERE event_type = 'CERTIFICATES_PENDING';

DELETE FROM audit_events
 WHERE event_type IN ('INVITATION_CREATED', 'INVITATION_SENT',
                      'INVITATION_ACCEPTED', 'INVITATION_REVOKED',
                      'INVITATION_EXPIRED', 'EMAIL_DELIVERY_FAILED',
                      'SIGNING_SESSION_CREATED', 'SIGNER_REGISTERED',
                      'SIGNING_TURN_CLAIMED', 'SIGNING_TURN_RELEASED',
                      'NEXT_SIGNER_ACTIVATED');

-- ── 5. the account status that only invitations created ──────────────────
-- PENDING_INVITE was the identity an invitation was addressed to before its
-- recipient registered. Nothing creates one now; the file→DB importer still
-- needs a row that cannot sign in for a username an imported workflow
-- references, so the state survives under a name that says what it is.
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_status;

UPDATE users SET status = 'PLACEHOLDER' WHERE status = 'PENDING_INVITE';

ALTER TABLE users
    ADD CONSTRAINT ck_users_status
    CHECK (status IN ('ACTIVE', 'DISABLED', 'PLACEHOLDER'));
