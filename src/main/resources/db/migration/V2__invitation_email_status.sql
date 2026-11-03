-- V2 (invitation delivery state, brief §37): separate "invitation created"
-- from "email sent / failed" so the owner's tracking view never implies
-- delivery that did not happen. Nullable: pre-existing rows predate the
-- tracking column and keep null (= unknown) instead of a false SENT.
ALTER TABLE invitations
    ADD COLUMN email_status VARCHAR(20) NULL;

ALTER TABLE invitations
    ADD CONSTRAINT ck_invitations_email_status
    CHECK (email_status IS NULL OR email_status IN ('PENDING', 'SENT', 'FAILED'));
