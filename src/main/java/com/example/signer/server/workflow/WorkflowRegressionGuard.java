package com.example.signer.server.workflow;

import java.util.List;

/**
 * Last line of defence against stale writes, applied by every store before
 * it overwrites a stored workflow: whatever the caller's in-memory copy
 * says, a save may never
 *
 * <ul>
 *   <li>move a workflow out of a terminal state (COMPLETED, CANCELLED,
 *       FAILED never change again),</li>
 *   <li>turn an accepted signature back into an unsigned slot, or</li>
 *   <li>drop or rewrite an accepted document revision.</li>
 * </ul>
 *
 * <p>The optimistic version check already rejects stale copies; this guard
 * makes the most damaging regressions impossible even if a future code path
 * forgets to reload under the lock.
 */
public final class WorkflowRegressionGuard {

    /** Hash of the placeholder revision 0 of a DRAFT workflow. */
    private static final String DRAFT_REVISION0 = "0".repeat(64);

    private WorkflowRegressionGuard() {
    }

    /** @throws WorkflowConflictException when {@code next} regresses {@code stored} */
    public static void check(SigningWorkflowAggregate stored, SigningWorkflowAggregate next) {
        if (stored.isTerminal() && next.status() != stored.status()) {
            throw new WorkflowConflictException("Workflow " + stored.workflowId()
                    + " is " + stored.status() + " and can no longer change"
                    + " (attempted " + next.status() + ")");
        }
        List<WorkflowSignerRecord> before = stored.signers();
        List<WorkflowSignerRecord> after = next.signers();
        for (WorkflowSignerRecord old : before) {
            if (!old.signed()) {
                continue;
            }
            WorkflowSignerRecord now = after.stream()
                    .filter(s -> s.order() == old.order())
                    .findFirst().orElse(null);
            if (now == null || !now.signed()) {
                throw new WorkflowConflictException("Workflow " + stored.workflowId()
                        + ": the accepted signature of signer " + old.order()
                        + " cannot be reverted");
            }
        }
        List<DocumentRevisionRecord> oldRevisions = stored.revisions();
        List<DocumentRevisionRecord> newRevisions = next.revisions();
        if (newRevisions.size() < oldRevisions.size()) {
            throw new WorkflowConflictException("Workflow " + stored.workflowId()
                    + ": accepted document revisions cannot be removed");
        }
        for (int i = 0; i < oldRevisions.size(); i++) {
            String oldHash = oldRevisions.get(i).sha256();
            boolean draftPlaceholder = i == 0 && DRAFT_REVISION0.equals(oldHash);
            if (!draftPlaceholder && !oldHash.equals(newRevisions.get(i).sha256())) {
                throw new WorkflowConflictException("Workflow " + stored.workflowId()
                        + ": accepted revision " + i + " cannot be rewritten");
            }
        }
    }
}
