package com.example.signer.server.api;

import com.example.signer.server.workflow.RemoteWorkflowService;
import com.example.signer.server.api.ApiContracts.AuditView;
import com.example.signer.server.api.ApiContracts.BindCertificateRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;

import com.example.signer.server.auth.AuthController;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.List;

/**
 * The coordination REST API (§11 of the task spec). The authenticated user is
 * resolved from the validated Bearer JWT (Spring Security, see
 * {@code server.security.SecurityConfig} — the
 * former Basic-auth filter) and injected via the
 * {@code X-Workflow-User} request attribute.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowRestController {

    private final RemoteWorkflowService service;

    /**
     * The operations that take several steps in a fixed order. Shared with
     * the website's controllers (Phase E) so the order cannot drift apart
     * between the two chains.
     */
    private final WorkflowOperations operations;


    public WorkflowRestController(RemoteWorkflowService service,
                                  WorkflowOperations operations) {
        this.service = service;
        this.operations = operations;
    }

    /** Creates a workflow (multipart: request part + original PDF part). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreateWorkflowResponse> create(
            @RequestPart("request") CreateWorkflowRequest request,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest servletRequest) throws IOException {
        String user = AuthController.requireUser(servletRequest);
        // Every signer slot has to name an account that already exists: a
        // slot carrying an e-mail address is refused rather than turned
        // into a passwordless placeholder. The check is in
        // WorkflowOperations.
        return ResponseEntity.status(HttpStatus.CREATED).body(operations.create(
                request, operations.requirePdf(file), user));
    }

    @GetMapping("/{workflowId}")
    public WorkflowView get(@PathVariable String workflowId,
                            HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.getWorkflow(workflowId, user);
    }

    @GetMapping("/my-tasks")
    public List<WorkflowView> myTasks(HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.myTasks(user);
    }

    /** Workflows created by the authenticated user (the sender's picker). */
    @GetMapping("/owned")
    public List<WorkflowView> owned(HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.ownedBy(user);
    }

    /**
     * The Signing Tasks workspace: every workflow the authenticated user
     * participates in as a signer, across all lifecycle states (unlike
     * {@code my-tasks}, which lists only actionable items).
     */
    @GetMapping("/signing-tasks")
    public ApiContracts.SigningTasksView
            signingTasks(HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.listParticipating(user);
    }

    @GetMapping("/{workflowId}/task")
    public TaskView task(@PathVariable String workflowId,
                         HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.getTask(workflowId, user);
    }

    /**
     * Claims the signing turn (§33). FLEXIBLE workflows: atomically reserves
     * the single turn — the loser of a race receives 409 TURN_ALREADY_CLAIMED.
     * SEQUENTIAL workflows: verifies the caller is the current signer.
     */
    @PostMapping("/{workflowId}/claim")
    public TaskView claim(@PathVariable String workflowId,
                          HttpServletRequest request) {
        String user = AuthController.requireUser(request);

        return service.claimTurn(workflowId, user);
    }


    /** Pending signer user ids who may hold the turn. */
    @GetMapping("/{workflowId}/eligible-signers")
    public List<String> eligibleSigners(@PathVariable String workflowId,
                                        HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.eligibleSigners(workflowId, user);
    }

    /**
     * Binds the caller's own certificate to their signer slot (§13/§14:
     * enrollment happens on the signer's machine). Authorization is derived
     * from the JWT — nobody can bind on behalf of someone else.
     */
    @PostMapping("/{workflowId}/signers/{userId}/certificate")
    public WorkflowView bindCertificate(@PathVariable String workflowId,
                                        @PathVariable String userId,
                                        @RequestBody BindCertificateRequest body,
                                        HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        if (!user.equals(userId)) {
            throw new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                    "You can only bind a certificate to your own signer slot");
        }
        return service.bindCertificate(workflowId, user, body);
    }

    /** Downloads the current revision (application/pdf). */
    @GetMapping("/{workflowId}/document")
    public ResponseEntity<byte[]> document(@PathVariable String workflowId,
                                           HttpServletRequest request) {
        String user = AuthController.requireUser(request);

        byte[] pdf = service.getDocument(workflowId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                // The name is the uploader's text, so it is not put in a
                // header as it stands — see WorkflowOperations.attachment.
                .header("Content-Disposition", WorkflowOperations.attachment(
                        service.getWorkflow(workflowId, user).documentName()))
                .body(pdf);
    }

    @GetMapping("/{workflowId}/revisions")
    public List<RevisionView> revisions(@PathVariable String workflowId,
                                        HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.getRevisions(workflowId, user);
    }

    @GetMapping("/{workflowId}/audit")
    public List<AuditView> audit(@PathVariable String workflowId,
                                 HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.getAudit(workflowId, user);
    }

    /** Submits a locally signed revision (multipart: metadata + signed PDF). */
    @PostMapping(value = "/{workflowId}/signatures",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SignatureSubmissionResponse submit(
            @PathVariable String workflowId,
            @RequestPart("request") SignatureSubmissionRequest request,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest servletRequest) throws IOException {
        String user = AuthController.requireUser(servletRequest);
        if (file == null || file.isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The signed PDF file is required");
        }

        return service.submitSignature(workflowId, user, file.getBytes(), request);
    }


    /** Cancels a workflow (creator or current signer, pre-completion only). */
    @PostMapping("/{workflowId}/cancel")
    public WorkflowView cancel(@PathVariable String workflowId,
                               HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.cancelWorkflow(workflowId, user);
    }
}
