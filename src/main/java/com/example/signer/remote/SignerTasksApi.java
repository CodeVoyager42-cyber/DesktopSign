package com.example.signer.remote;

import com.example.signer.server.api.ApiContracts.AuditView;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.SigningTasksView;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;

import java.util.List;

/**
 * What a SIGNER does against the coordination server: list tasks, read a
 * task and its document, take the turn, bind a certificate, submit the
 * signed revision. Code that only signs depends on this, not on the whole
 * {@link WorkflowApiClient} (interface segregation).
 */
public interface SignerTasksApi {

    List<WorkflowView> myTasks();

    SigningTasksView signingTasks();

    TaskView task(String workflowId);

    byte[] document(String workflowId);

    SignatureSubmissionResponse submitSignature(String workflowId,
                                                SignatureSubmissionRequest request,
                                                byte[] signedPdf);

    List<RevisionView> revisions(String workflowId);

    List<AuditView> audit(String workflowId);

    TaskView claimTurn(String workflowId);

    List<String> eligibleSigners(String workflowId);

    WorkflowView bindMyCertificate(String workflowId, String certificateSha256);
}
