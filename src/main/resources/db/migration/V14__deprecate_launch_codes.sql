-- V14 — mark launch_codes deprecated (scope B removal, step 1a).
--
-- COMMENTS ONLY. Nothing is dropped, altered, renamed or rewritten: after
-- this migration the table, its columns, its constraints and its two indexes
-- are exactly what V8 created, and the application still reads and writes it
-- normally. Running this against a live deployment changes no behaviour.
--
-- Why a migration at all, rather than going straight to the drop: the drop is
-- the one step of this removal that cannot be undone with `git revert`, so it
-- gets its own migration (V15) that runs only after the rows have been
-- exported. This one exists so that anybody who opens the schema -- a DBA, a
-- psql session, a generated diagram -- is told the table is on its way out
-- before the code that fills it disappears, instead of finding an unexplained
-- orphan table later.
--
-- The desktop launch-code path (website "Sign now" -> signflow://launch -> a
-- short-lived task session) is being removed deliberately. The e-mail/guest
-- invitation path is NOT affected and keeps its own tables.
--
-- Audit events DESKTOP_SESSION_OPENED and DESKTOP_SESSION_REVOKED are NOT
-- touched here and will NOT be deleted: they are historical evidence of
-- sessions that really happened, and the product relies on the audit trail
-- for disputes. Their enum values stay in the code, deprecated.

COMMENT ON TABLE launch_codes IS
    'DEPRECATED (V14): the desktop launch-code path is being removed. Still '
    'written by LaunchCodeService until step 1c; dropped by V15 after the '
    'rows are exported. Do not build anything new on this table.';

COMMENT ON COLUMN launch_codes.code_hash IS
    'DEPRECATED (V14). SHA-256 of the one-time code; the code itself was '
    'never stored.';

COMMENT ON COLUMN launch_codes.username IS
    'DEPRECATED (V14). The account the code was issued to.';

COMMENT ON COLUMN launch_codes.workflow_id IS
    'DEPRECATED (V14). The workflow the code was bound to.';

COMMENT ON COLUMN launch_codes.signer_order IS
    'DEPRECATED (V14). The signer slot the code was bound to.';

COMMENT ON COLUMN launch_codes.purpose IS
    'DEPRECATED (V14). Always SIGN.';

COMMENT ON COLUMN launch_codes.min_app_version IS
    'DEPRECATED (V14). Minimum desktop version at issue time. Note that the '
    'min-version property itself is RETAINED: HealthController still '
    'advertises it as a client-compatibility floor.';

COMMENT ON COLUMN launch_codes.created_at IS
    'DEPRECATED (V14).';

COMMENT ON COLUMN launch_codes.expires_at IS
    'DEPRECATED (V14). Two minutes after created_at.';

COMMENT ON COLUMN launch_codes.consumed_at IS
    'DEPRECATED (V14). Set by the single atomic UPDATE that consumed the '
    'code; NULL means never exchanged.';

COMMENT ON COLUMN launch_codes.consumed_app_version IS
    'DEPRECATED (V14).';

COMMENT ON COLUMN launch_codes.consumed_device IS
    'DEPRECATED (V14).';

COMMENT ON COLUMN launch_codes.revoked_at IS
    'DEPRECATED (V14). Ended the desktop session opened with the code.';
