-- V15 — launch_codes is in use again; undo V14's deprecation notes.
--
-- COMMENTS ONLY, like V14. Nothing is created, dropped, altered or renamed:
-- the table, its 13 columns, its constraints and its 2 indexes are still
-- exactly what V8 created, and the rows are untouched.
--
-- Why this exists instead of deleting V14: V14 had already been applied to
-- real databases (flyway_schema_history shows it succeeded). Deleting the
-- file would leave Flyway with an applied migration it cannot resolve
-- locally, and `validate` then fails every start-up against those databases
-- until each one is rebuilt. A migration is a fact about a database, not a
-- line of source that can be taken back, so the way to undo one is to roll
-- forward.
--
-- The history this records: the desktop launch-code path was removed (V14
-- marked the table on its way out), and then restored, because the product's
-- signing flow IS the hand-off — sign in on the website, upload a PDF, click
-- Sign, finish the signature on the desktop with the smart card. The table
-- is load-bearing for that, not legacy.
--
-- The invitation workflow that was removed alongside it did NOT come back.
-- The `invitations` table is a different matter and keeps its own state.

COMMENT ON TABLE launch_codes IS
    'One-time codes for the website -> desktop signing hand-off. A signed-in '
    'user asks for a code for ONE signing task; the website opens '
    'signflow://launch?v=1&c=<code>; the desktop exchanges it once for a '
    'short-lived token limited to that task. Only the SHA-256 of a code is '
    'stored. Deprecated by V14 and un-deprecated by V15: the path was removed '
    'and then restored, because this hand-off is the product''s signing flow.';

COMMENT ON COLUMN launch_codes.code_hash IS
    'SHA-256 of the one-time code; the code itself is never stored.';

COMMENT ON COLUMN launch_codes.username IS
    'The account the code was issued to.';

COMMENT ON COLUMN launch_codes.workflow_id IS
    'The workflow the code is bound to.';

COMMENT ON COLUMN launch_codes.signer_order IS
    'The signer slot the code is bound to.';

COMMENT ON COLUMN launch_codes.purpose IS
    'Always SIGN.';

COMMENT ON COLUMN launch_codes.min_app_version IS
    'Minimum desktop version at issue time; an older desktop is refused with '
    'HTTP 426 at exchange.';

COMMENT ON COLUMN launch_codes.created_at IS
    'When the code was issued. The per-minute issue throttle reads this.';

COMMENT ON COLUMN launch_codes.expires_at IS
    'Two minutes after created_at.';

COMMENT ON COLUMN launch_codes.consumed_at IS
    'Set by the single atomic UPDATE that consumes the code; NULL means never '
    'exchanged. A consumed code stays consumed even if a later check fails.';

COMMENT ON COLUMN launch_codes.consumed_app_version IS
    'Desktop version that exchanged the code.';

COMMENT ON COLUMN launch_codes.consumed_device IS
    'Device name the desktop reported, for the "opened on device X" notice.';

COMMENT ON COLUMN launch_codes.revoked_at IS
    'Ends the desktop session opened with the code ("this wasn''t me").';
