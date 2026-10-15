-- Owner views ("Sent for signature", inviter picker) look workflows up by
-- creator instead of scanning every workflow.
CREATE INDEX IF NOT EXISTS idx_workflows_created_by ON workflows (created_by);
