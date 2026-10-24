package com.example.signer.server.api;

import com.example.signer.server.api.ApiContracts.CreateInvitationRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.InvitationStatusView;
import com.example.signer.server.api.ApiContracts.InvitationView;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.server.workflow.RemoteWorkflowService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The three workflow operations that are more than one service call, in the
 * one place both chains reach them from.
 *
 * <p>Creating a workflow, inviting a slot and reading invitation status each
 * need several steps in a particular order — and each of those orders carries
 * a rule. Starting a workflow has to prepare the e-mail-addressed slots
 * before the workflow exists and send their invitations only after it does.
 * Inviting a slot has to check the caller may see the workflow at all
 * <em>before</em> telling them how many slots it has. Invitation status has
 * to run the same access check and then re-check ownership in the service.
 *
 * <p>Phase E gave the website its own controllers (deliberately, see
 * {@code web/WebWorkflowController}), and a second copy of these orderings is
 * how two chains quietly stop agreeing: a check tightened on one and
 * forgotten on the other. So the <b>sequence</b> lives here and the
 * controllers keep only what is theirs — resolving the caller from their own
 * credential, and the HTTP status of the answer.
 *
 * <p>This holds no authorization rules of its own. Every path starts with
 * {@link RemoteWorkflowService#getWorkflow}, whose uniform 404 is the Phase 3
 * guard, and the ownership rules for invitations stay in
 * {@link InvitationService}.
 */
@Component
public class WorkflowOperations {

    private final RemoteWorkflowService workflows;

    /** Present only in database mode: e-mail invitations at creation time. */
    private final ObjectProvider<WorkflowInvitationCoordinator> coordinator;

    private final ObjectProvider<InvitationService> invitations;

    public WorkflowOperations(RemoteWorkflowService workflows,
                              ObjectProvider<WorkflowInvitationCoordinator> coordinator,
                              ObjectProvider<InvitationService> invitations) {
        this.workflows = workflows;
        this.coordinator = coordinator;
        this.invitations = invitations;
    }

    /**
     * The uploaded part has to be a PDF, and has to be there. Both chains
     * take the document as multipart, so both ask this.
     */
    public byte[] requirePdf(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The original PDF file is required");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "Only PDF documents can start a signing workflow");
        }
        return file.getBytes();
    }

    /**
     * Starts a workflow, and in database mode turns its e-mail-addressed
     * slots into invited accounts that are then e-mailed their invitation.
     *
     * <p>The returned links are the only time they exist outside the e-mail:
     * only the token hash is persisted, so no later read can reproduce them.
     */
    public CreateWorkflowResponse create(CreateWorkflowRequest request, byte[] pdf,
                                         String caller) {
        WorkflowInvitationCoordinator invites = this.coordinator.getIfAvailable();
        if (invites == null) {
            // File mode: no accounts, no addresses, nothing to invite.
            return new CreateWorkflowResponse(
                    this.workflows.createWorkflow(request, pdf, caller), List.of());
        }
        WorkflowInvitationCoordinator.Preparation prepared =
                invites.prepare(request, caller);
        WorkflowView workflow =
                this.workflows.createWorkflow(prepared.request(), pdf, caller);
        List<InvitationLinkView> links = prepared.pendingInvites().isEmpty()
                ? List.of()
                : invites.invite(workflow.workflowId(), prepared.pendingInvites(), caller);
        return new CreateWorkflowResponse(workflow, links);
    }

    /**
     * Invites one signer slot of an existing workflow.
     *
     * <p>The access check runs first and the slot number is validated against
     * what that workflow actually has, so this never reports the shape of a
     * workflow the caller cannot see.
     */
    public InvitationView invite(String workflowId, CreateInvitationRequest request,
                                 String caller) {
        WorkflowView workflow = this.workflows.getWorkflow(workflowId, caller);
        // signerOrder is 1-based everywhere: database slot order, invitation
        // rows, the pickers in both clients.
        if (request.signerOrder() < 1 || request.signerOrder() > workflow.totalSignatures()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "Signer order " + request.signerOrder() + " is out of range (1.."
                            + workflow.totalSignatures() + ")");
        }
        InvitationService service = this.invitations.getIfAvailable();
        if (service == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "Invitations are not enabled on this server.");
        }
        String rawToken = service.invite(workflowId, request.signerOrder(),
                request.email(), caller);
        return new InvitationView(null, workflowId, workflow.documentName(),
                request.signerOrder(), request.email(), caller,
                InvitationEntity.STATUS_PENDING, null,
                service.senderVisibleUrl(rawToken));
    }

    /**
     * Invitation status per signer slot, for whoever is tracking the
     * workflow. No raw token: status and timestamps only. The workflow access
     * guard runs first, then {@link InvitationService} re-checks that the
     * caller created it — the same rule resend and revoke apply.
     */
    public List<InvitationStatusView> invitationStatus(String workflowId, String caller) {
        this.workflows.getWorkflow(workflowId, caller);
        InvitationService service = this.invitations.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        Map<Integer, Boolean> slotSigned = new HashMap<>();
        try {
            for (TaskView.StepView step : this.workflows.getTask(workflowId, caller)
                    .signers()) {
                slotSigned.put(step.order(), step.signed());
            }
        } catch (ApiException ignored) {
            // Tracking stays invitation-only when the task view is not
            // available to this caller (an owner who signs nothing).
        }
        return service.listForWorkflow(workflowId, caller, slotSigned);
    }

    /**
     * A {@code Content-Disposition} value for a document download.
     *
     * <p>A document's name is whatever the uploader typed — it is validated
     * only for being non-blank — and both download routes put it in a header.
     * Quoted straight in, a name containing a {@code "} ends the filename
     * early and the rest is read as more header parameters; one containing a
     * newline is a header-splitting attempt that the container answers with a
     * 500 rather than a download. Neither is reachable through this method:
     * anything outside a conservative set becomes {@code _}, and a name left
     * with nothing usable becomes {@code document.pdf}.
     *
     * <p>Accents and non-Latin scripts are flattened too, which is a real
     * cost for anyone naming files in their own language. It is accepted for
     * now because the alternative — RFC 5987's {@code filename*} — deserves
     * its own test for how each browser reads it, rather than being slipped
     * in behind a security fix.
     */
    public static String attachment(String documentName) {
        String safe = documentName == null ? ""
                : documentName.trim().replaceAll("[^A-Za-z0-9 ._-]", "_");
        if (safe.length() > 100) {
            safe = safe.substring(0, 100);
        }
        // A name that was all punctuation leaves underscores and dots behind.
        if (safe.isBlank() || safe.chars().noneMatch(Character::isLetterOrDigit)) {
            safe = "document.pdf";
        }
        return "attachment; filename=\"" + safe + "\"";
    }
}
