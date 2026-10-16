package com.example.signer.client;

import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.InvitationStatusView;
import com.example.signer.server.api.ApiContracts.InvitationView;
import com.example.signer.server.api.ApiContracts.WorkflowView;

import java.util.List;

/**
 * What the SENDER of a workflow does: create it, follow and cancel it, and
 * manage its invitations. Separate from {@link SignerTasksApi} so signing
 * code cannot reach owner operations (interface segregation).
 */
public interface WorkflowOwnerApi {

    CreateWorkflowResponse createWorkflow(String documentName,
                                          List<CreateWorkflowRequest.SignerSpec> signers,
                                          byte[] originalPdf);

    CreateWorkflowResponse createWorkflow(String documentName, String signingPolicy,
                                          List<CreateWorkflowRequest.SignerSpec> signers,
                                          byte[] originalPdf);

    List<WorkflowView> myWorkflows();

    WorkflowView cancel(String workflowId);

    InvitationView invite(String workflowId, int signerOrder, String email);

    List<InvitationStatusView> workflowInvitations(String workflowId);

    InvitationLinkView resendInvitation(long invitationId);

    InvitationLinkInfo invitationLinkInfo();
}
