package com.example.signer.server.workflow;

import com.example.signer.server.db.JpaWorkflowStore;
import java.util.List;

/**
 * Persistence seam for server-coordinated workflows (brief Phase 1).
 *
 * <p>The workflow engine ({@link RemoteWorkflowService}) depends only on this
 * interface. Two implementations exist, selected by the
 * {@code signature.remote-server.database.enabled} property:
 *
 * <ul>
 *   <li>{@link WorkflowFileStore} — JSON snapshots + revision files
 *       (legacy/dev mode, the default),</li>
 *   <li>{@code JpaWorkflowStore} — PostgreSQL
 *       (users, workflows, signers, document versions, audit events) with
 *       PDF bytes still on disk via {@code DocumentFileStorage}.</li>
 * </ul>
 *
 * <p>Contract notes: {@link #load} returns {@code null} for an unknown id;
 * {@link #save} persists the full aggregate snapshot; revision files are
 * append-only (writing an existing revision is refused); {@link #lockFor}
 * returns the monitor that serialises mutations of one workflow.
 *
 * <p><b>Mutation protocol</b> (every caller): take {@link #lockFor}, load
 * the aggregate INSIDE the lock, validate the command against that fresh
 * copy, mutate, {@link #save}, release. {@link #save} is optimistic: it
 * throws {@link WorkflowConflictException} when the stored version differs
 * from the version the aggregate was loaded at, when a NEW aggregate would
 * overwrite an existing workflow, or when the save would regress a terminal
 * state or an accepted signature. The JVM lock only serialises one server
 * process; the version check (and, in database mode, a row lock) is what
 * keeps a second process from overwriting newer state.
 */
public interface WorkflowStore {

    /** Whether a workflow with this id exists. */
    boolean exists(String workflowId);

    /** Loads the aggregate for {@code workflowId}, or {@code null} when absent. */
    SigningWorkflowAggregate load(String workflowId);

    /**
     * Persists the aggregate snapshot and advances its version.
     *
     * @throws WorkflowConflictException when the stored workflow is newer
     *         than {@code aggregate}, or the save would regress a terminal
     *         state / accepted signature
     */
    void save(SigningWorkflowAggregate aggregate);

    /**
     * Removes revision {@code number} written by a submission whose state
     * change could NOT be committed (compensation, so the revision chain
     * never contains an orphan that blocks every later attempt). Must only
     * be called for a revision that no committed state references.
     */
    void discardUncommittedRevision(String workflowId, int number);

    /** The per-workflow lock used to serialise submissions. */
    Object lockFor(String workflowId);

    /** All known workflow ids. */
    List<String> listWorkflowIds();

    /**
     * Ids of the workflows in which {@code userId} holds a signer slot.
     * Task lists use this instead of loading every workflow. The default
     * scans (fine for the small file store); the database store answers
     * with one indexed query.
     */
    default List<String> listWorkflowIdsForSigner(String userId) {
        return listWorkflowIds().stream()
                .filter(id -> {
                    SigningWorkflowAggregate wf = loadQuietly(id);
                    return wf != null && wf.signers().stream()
                            .anyMatch(s -> s.userId().equals(userId));
                })
                .toList();
    }

    /** Ids of the workflows created by {@code userId} (default: scan). */
    default List<String> listWorkflowIdsCreatedBy(String userId) {
        return listWorkflowIds().stream()
                .filter(id -> {
                    SigningWorkflowAggregate wf = loadQuietly(id);
                    return wf != null && userId != null && userId.equals(wf.createdBy());
                })
                .toList();
    }

    /** {@link #load}, but {@code null} for an unreadable workflow. */
    private SigningWorkflowAggregate loadQuietly(String workflowId) {
        try {
            return load(workflowId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Stages the uploaded original for a DRAFT workflow (pre-revision 0). */
    void stageOriginal(String workflowId, byte[] bytes);

    /** The staged original of a DRAFT workflow, or {@code null} when absent. */
    byte[] readStagedOriginal(String workflowId);

    /** Persists {@code bytes} as the immutable revision {@code number}. */
    void writeRevision(String workflowId, int number, byte[] bytes);

    /** Reads the stored bytes of revision {@code number}. */
    byte[] readRevision(String workflowId, int number);

    /** Whether the immutable revision {@code number} exists. */
    boolean existsRevision(String workflowId, int number);
}
