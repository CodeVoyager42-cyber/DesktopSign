-- V3 (P0 concurrency hardening):
--
-- * workflows.version: JPA @Version optimistic-lock column. Every aggregate
--   save checks it and increments it, so a request working on a stale copy
--   of a workflow gets a 409 conflict instead of silently overwriting newer
--   state (lost signatures, COMPLETED -> CANCELLED, ...). Existing rows
--   start at 0.
-- * invitations(workflow_id, signer_order): the only index on these columns
--   so far was the partial "one PENDING per slot" index; every invitation
--   lookup by workflow scanned the table.
ALTER TABLE workflows
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_invitations_workflow_slot
    ON invitations (workflow_id, signer_order);
