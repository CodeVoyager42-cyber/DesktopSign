-- Rollback for V17__drop_invitation_tables.sql (scope A, PR-5).
--
-- Flyway NEVER runs this. It is not under classpath:db/migration — the only
-- location Flyway is configured to scan (DbPersistenceConfiguration) — on
-- purpose: undoing a migration is a decision somebody makes, not something a
-- tool should do on its own at start-up.
--
-- HOW TO USE IT
--
--   1. Recreate the shape (this file):
--        psql -U postgres -h localhost -d signer -f scripts/db/V17_down.sql
--
--   2. Put the rows back from the export (the manifest names the CSV and
--      carries its SHA-256 — check it before trusting the file). NAME THE
--      COLUMNS: \copy maps positionally and ignores the header, and an export
--      taken from a database that had not yet reached V2/V7 holds 11 columns,
--      not the 17 recreated below. The manifest prints the exact command with
--      the column list that export actually has:
--        psql -U postgres -h localhost -d signer \
--          -c "\copy invitations (id, workflow_id, ...) from '<csv>' with csv header"
--      The columns an older export does not carry keep their defaults —
--      email_status NULL, the guest_code_* counters 0 — which is right: that
--      database never had a value for them.
--
--   3. Put the sequence past the restored ids, or the next insert collides:
--        psql -U postgres -h localhost -d signer \
--          -c "select setval('invitations_id_seq', (select max(id) from invitations))"
--
--   4. Tell Flyway that V17 is no longer applied, or `validate` fails every
--      start-up against this database:
--        flyway repair
--      or, without the Flyway CLI:
--        delete from flyway_schema_history where version = '17';
--
-- WHAT THIS CANNOT DO. It cannot invent rows. Without the CSV from
-- scripts/export-invitations.sh this file gives you an empty table of the
-- right shape and nothing else — which is the whole reason the export is a
-- separate, earlier step with a guard in V17 enforcing it.
--
-- WHAT IT DOES NOT NEED TO DO. Nothing in audit_events: V17 does not touch
-- it, so there is nothing there to restore. If the INVITATION_* counts in
-- the export manifest do not match what the database holds now, something
-- other than V17 changed them and this rollback is not the problem.

-- ── The table, exactly as V1 created it ─────────────────────────────────
CREATE TABLE invitations (
    id           BIGSERIAL PRIMARY KEY,
    workflow_id  BIGINT       NOT NULL,
    signer_order INT          NOT NULL,
    email        VARCHAR(255) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,   -- SHA-256 of the raw token; raw token never stored
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    invited_by   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    accepted_at  TIMESTAMPTZ      NULL,
    accepted_by  VARCHAR(100)     NULL,
    CONSTRAINT uq_invitations_token UNIQUE (token_hash),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED',
        'EXPIRED', 'REVOKED', 'DECLINED')),
    CONSTRAINT fk_invitations_workflow FOREIGN KEY (workflow_id)
        REFERENCES workflows (id) ON DELETE CASCADE
);

-- ── V2: delivery state of the invitation e-mail ─────────────────────────
ALTER TABLE invitations
    ADD COLUMN email_status VARCHAR(20) NULL;

ALTER TABLE invitations
    ADD CONSTRAINT ck_invitations_email_status
    CHECK (email_status IS NULL OR email_status IN ('PENDING', 'SENT', 'FAILED'));

-- ── V7: guest verification codes ────────────────────────────────────────
ALTER TABLE invitations
    ADD COLUMN guest_code_hash       CHAR(64)    NULL,
    ADD COLUMN guest_code_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN guest_code_sent_at    TIMESTAMPTZ NULL,
    ADD COLUMN guest_code_attempts   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN guest_code_failures   INTEGER     NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_invitations_guest_code_attempts CHECK (guest_code_attempts >= 0),
    ADD CONSTRAINT ck_invitations_guest_code_failures CHECK (guest_code_failures >= 0);

-- ── The four indexes: V1 gave three, V3 the fourth ──────────────────────
CREATE INDEX idx_invitations_email  ON invitations (email);
CREATE INDEX idx_invitations_status ON invitations (status);

CREATE UNIQUE INDEX uq_invitations_one_pending_per_slot
    ON invitations (workflow_id, signer_order)
    WHERE status = 'PENDING';

CREATE INDEX idx_invitations_workflow_slot
    ON invitations (workflow_id, signer_order);

-- ── The column V17 took off workflow_signers ────────────────────────────
ALTER TABLE workflow_signers
    ADD COLUMN IF NOT EXISTS invited_at TIMESTAMPTZ NULL;
