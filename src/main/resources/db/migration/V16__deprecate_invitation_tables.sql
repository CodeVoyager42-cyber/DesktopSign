-- V16 — mark the invitation workflow's storage deprecated (scope A, PR-1).
--
-- COMMENTS ONLY. Nothing is dropped, altered, renamed or rewritten: after
-- this migration the `invitations` table, its 17 columns, its primary key,
-- its 6 named constraints and its 4 indexes are exactly what V1, V2, V3 and
-- V7 left behind, and the rows are untouched. Running this against a live
-- deployment changes no behaviour.
--
-- Why a migration at all, rather than going straight to the drop: the drop is
-- the one step of this removal that cannot be undone with `git revert`, so it
-- gets its own migration (V17) that runs only after the rows have been
-- exported to CSV and the export has been signed off. This one exists so that
-- anybody who opens the schema -- a DBA, a psql session, a generated diagram
-- -- is told the table is on its way out, instead of finding an unexplained
-- orphan table later.
--
-- What was removed, and when: the e-mail/guest invitation workflow. One user
-- could invite another by e-mail address; the invitee opened a tokenised link
-- (/sign/{token}), proved control of the address with a 6-digit code, and
-- signed as a guest with no account. All of it went in commit 02eb12b --
-- InvitationService, InvitationRestController, SignPageController,
-- GuestAccessPolicy, InvitationEntity and the rest -- and nothing reads or
-- writes this table any more.
--
-- The only signing flow now is: sign in on the website, upload a PDF, click
-- Sign, confirm "Open SignFlow", finish the signature on the desktop with a
-- smart card. That hand-off runs on `launch_codes`, which is NOT affected
-- here and is load-bearing (see V15).
--
-- AUDIT EVENTS ARE NOT TOUCHED AND WILL NOT BE DELETED. The rows in
-- audit_events bearing INVITATION_CREATED, INVITATION_SENT,
-- INVITATION_ACCEPTED, INVITATION_REVOKED and INVITATION_EXPIRED are
-- historical evidence of invitations that really were sent and accepted, and
-- the product's dispute story rests on that trail. Their enum values stay in
-- the code, deprecated -- see AuditEventType, "Historical only -- never
-- delete". No migration in this removal contains a DELETE.

COMMENT ON TABLE invitations IS
    'DEPRECATED (V16): the e-mail/guest invitation workflow was removed in '
    'commit 02eb12b and nothing reads or writes this table any more. Dropped '
    'by V17 after the rows are exported to CSV. Do not build anything new on '
    'it. The audit_events rows for INVITATION_* are a separate matter and are '
    'retained permanently as historical evidence.';

COMMENT ON COLUMN invitations.id IS
    'DEPRECATED (V16).';

COMMENT ON COLUMN invitations.workflow_id IS
    'DEPRECATED (V16). The workflow the invitation belonged to.';

COMMENT ON COLUMN invitations.signer_order IS
    'DEPRECATED (V16). The signer slot the invitation filled.';

COMMENT ON COLUMN invitations.email IS
    'DEPRECATED (V16). The invited address. PERSONAL DATA: this column is the '
    'reason V17 is preceded by an export rather than a plain drop -- the '
    'export has to be held under the audit retention policy, not left in a '
    'developer directory.';

COMMENT ON COLUMN invitations.token_hash IS
    'DEPRECATED (V16). SHA-256 of the raw invitation token; the token itself '
    'was never stored.';

COMMENT ON COLUMN invitations.status IS
    'DEPRECATED (V16). PENDING, ACCEPTED, EXPIRED, REVOKED or DECLINED. Rows '
    'left PENDING at removal are invitations nobody will ever be able to '
    'accept: the routes answer 404 as of 02eb12b, and no notice was sent to '
    'the invitees (an accepted consequence, recorded in the audit register).';

COMMENT ON COLUMN invitations.invited_by IS
    'DEPRECATED (V16). The account that sent the invitation.';

COMMENT ON COLUMN invitations.created_at IS
    'DEPRECATED (V16).';

COMMENT ON COLUMN invitations.expires_at IS
    'DEPRECATED (V16). Enforced by InvitationExpirySweeper, itself removed.';

COMMENT ON COLUMN invitations.accepted_at IS
    'DEPRECATED (V16). NULL means the invitation was never opened.';

COMMENT ON COLUMN invitations.accepted_by IS
    'DEPRECATED (V16). The account the invitee registered as, if any.';

COMMENT ON COLUMN invitations.email_status IS
    'DEPRECATED (V16). Added by V2 to separate "invitation created" from '
    '"e-mail sent/failed". NULL for rows predating V2. Note that the '
    'EMAIL_DELIVERY_FAILED audit event is a DIFFERENT thing and is still '
    'current: account e-mail (address confirmation, password reset) can still '
    'fail, and SMTP is RETAINED for it.';

COMMENT ON COLUMN invitations.guest_code_hash IS
    'DEPRECATED (V16). Added by V7. SHA-256 of the 6-digit guest code; the '
    'code itself was never stored.';

COMMENT ON COLUMN invitations.guest_code_expires_at IS
    'DEPRECATED (V16). Added by V7.';

COMMENT ON COLUMN invitations.guest_code_sent_at IS
    'DEPRECATED (V16). Added by V7. Fed the resend throttle.';

COMMENT ON COLUMN invitations.guest_code_attempts IS
    'DEPRECATED (V16). Added by V7. Wrong entries for the current code.';

COMMENT ON COLUMN invitations.guest_code_failures IS
    'DEPRECATED (V16). Added by V7. Wrong entries in total; past the limit '
    'the invitation had to be re-sent by its owner.';

-- One column outside the table goes with it. Dropped by V17 alongside
-- `invitations`, because the only thing that ever set it was the invitation
-- coordinator: a signer slot now names an account that already exists, so
-- there is no moment at which a signer is "invited" and not yet registered.
COMMENT ON COLUMN workflow_signers.invited_at IS
    'DEPRECATED (V16). Set only by the removed invitation coordinator; '
    'dropped by V17. A signer slot now names an existing account, so there is '
    'no invited-but-unregistered state to record.';
