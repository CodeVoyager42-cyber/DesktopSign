package com.example.signer.server.api;

import com.example.signer.server.workflow.RemoteWorkflowService;
import com.example.signer.server.api.ApiContracts.AuditView;
import com.example.signer.server.api.ApiContracts.BindCertificateRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;

import com.example.signer.server.auth.AuthController;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.List;

/**
 * The coordination REST API. The authenticated user is resolved from the
 * validated Bearer JWT (Spring Security, see
 * {@code server.security.SecurityConfig}) and injected via the
 * {@code X-Workflow-User} request attribute.
 *
 * <p>Every route here is about the caller's own document: they upload it,
 * register their certificate for it, download the revision to sign and
 * submit the signature. No route names another person.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowRestController {

    private final RemoteWorkflowService service;

    /** What both chains share — the PDF check and the download header. */
    private final WorkflowOperations operations;

    public WorkflowRestController(RemoteWorkflowService service,
                                  WorkflowOperations operations) {
        this.service = service;
        this.operations = operations;
    }

    /** Uploads a document to sign (multipart: request part + original PDF part). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkflowView> create(
            @RequestPart("request") CreateWorkflowRequest request,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest servletRequest) throws IOException {
        String user = AuthController.requireUser(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createWorkflow(
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

    /** Documents owned by the authenticated user. */
    @GetMapping("/owned")
    public List<WorkflowView> owned(HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.ownedBy(user);
    }

    /**
     * The documents workspace: every document of the authenticated user,
     * across all lifecycle states (unlike {@code my-tasks}, which lists only
     * actionable items).
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
     * Registers the caller's own certificate for their document: enrollment
     * happens on the machine the token is plugged into. Authorization is
     * derived from the JWT — nobody can bind on behalf of someone else.
     */
    @PostMapping("/{workflowId}/signers/{userId}/certificate")
    public WorkflowView bindCertificate(@PathVariable String workflowId,
                                        @PathVariable String userId,
                                        @RequestBody BindCertificateRequest body,
                                        HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        if (!user.equals(userId)) {
            throw new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                    "You can only register a certificate for your own document");
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

    /** Cancels a workflow (the owner, pre-completion only). */
    @PostMapping("/{workflowId}/cancel")
    public WorkflowView cancel(@PathVariable String workflowId,
                               HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return service.cancelWorkflow(workflowId, user);
    }
}
