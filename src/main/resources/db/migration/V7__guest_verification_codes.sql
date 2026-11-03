-- V7 — guest verification codes (security Phase 3).
--
-- Additive only. An invitation link alone no longer opens a guest signing
-- session: the server e-mails a one-time 6-digit code to the invited
-- address, and the guest session opens only with that code. So neither the
-- sender (who may have seen the link) nor someone the email was forwarded
-- to can sign in the invitee's place without also reading their mailbox.
--
--   guest_code_hash        SHA-256 of the current code (never the code)
--   guest_code_expires_at  the current code stops working after this
--   guest_code_sent_at     when the current code was sent (resend throttle)
--   guest_code_attempts    wrong entries for the current code
--   guest_code_failures    wrong entries in total; above the limit the
--                          invitation must be re-sent by its owner
ALTER TABLE invitations
    ADD COLUMN guest_code_hash       CHAR(64)    NULL,
    ADD COLUMN guest_code_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN guest_code_sent_at    TIMESTAMPTZ NULL,
    ADD COLUMN guest_code_attempts   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN guest_code_failures   INTEGER     NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_invitations_guest_code_attempts CHECK (guest_code_attempts >= 0),
    ADD CONSTRAINT ck_invitations_guest_code_failures CHECK (guest_code_failures >= 0);
