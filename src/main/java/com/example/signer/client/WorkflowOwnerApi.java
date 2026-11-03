package com.example.signer.client;

import com.example.signer.server.api.ApiContracts.WorkflowView;

import java.util.List;

/**
 * What the OWNER of a document does: upload it, follow it and cancel it.
 * Separate from {@link SignerTasksApi} so signing code cannot reach owner
 * operations (interface segregation).
 */
public interface WorkflowOwnerApi {

    /** Uploads a PDF to sign; the caller is its only signer. */
    WorkflowView createWorkflow(String documentName, byte[] originalPdf);

    List<WorkflowView> myWorkflows();

    WorkflowView cancel(String workflowId);
}
