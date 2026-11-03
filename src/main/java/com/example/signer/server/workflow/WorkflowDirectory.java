package com.example.signer.server.workflow;

/**
 * Human directory for the signing-tasks workspace: resolves the display
 * name and email of a workflow user id so the UI can show real sender
 * names ("From Jane Smith") instead of raw ids. Optional seam — file mode
 * has no account store beyond the configured user list, so it falls back
 * to the id itself.
 */
public interface WorkflowDirectory {

    /** Resolves the profile of {@code userId}, or {@code null} when unknown. */
    Profile profileOf(String userId);

    /** Human directory entry of one workflow user. */
    record Profile(String displayName, String email) {
    }
}
