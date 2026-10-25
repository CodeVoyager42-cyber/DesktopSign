package com.example.signer.server.web;

import com.example.signer.server.api.ApiContracts.AuditView;
import com.example.signer.server.api.ApiContracts.CreateInvitationRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.InvitationStatusView;
import com.example.signer.server.api.ApiContracts.InvitationView;
import com.example.signer.server.api.ApiContracts.LaunchCodeIssued;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SigningTasksView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.WorkflowOperations;
import com.example.signer.server.launch.LaunchCodeService;
import com.example.signer.server.workflow.RemoteWorkflowService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * What the website may do with workflows: read them (Phase E part 1), start,
 * invite to, track and cancel them (part 2), and take the finished document
 * away at the end (part 3). Each route is one line
 * over {@link RemoteWorkflowService} or {@link WorkflowOperations} — the same
 * service, the same arguments and the same refusals the desktop's
 * {@code /api/workflows} gets.
 *
 * <p><b>Why a second controller rather than a second mapping on the first.</b>
 * The two live in different security domains (Phase C §3): this one is reached
 * with a session cookie and a CSRF token, that one with a bearer JWT, and
 * neither chain parses the other's credential. They also learn who is calling
 * in different ways — here from the {@link Authentication} the session chain
 * loaded, there from the request attribute the bearer filter sets. Hanging
 * {@code /web/api/**} off the existing controller would put both domains
 * behind one set of annotations, where a mistake in a route's method or
 * matcher exposes both at once. Two thin controllers cost a few lines and
 * keep the blast radius at one domain.
 *
 * <p><b>No authorization logic of its own.</b> Every method passes the
 * session's username to the service and returns what comes back, so the
 * Phase 3 rules apply here unchanged and by construction: a stranger gets the
 * same 404 as for a workflow that does not exist, and the lists contain only
 * what the caller takes part in. {@code WebWorkflowReadHttpTests} re-proves
 * that over cookies rather than assuming it carries across.
 *
 * <p><b>What the multi-step operations share.</b> Starting a workflow and
 * inviting a slot are not single service calls — they are a sequence, and the
 * sequence carries rules (prepare the e-mailed slots before the workflow
 * exists, send after; check access before revealing how many slots there
 * are). Those sequences live in {@link WorkflowOperations}, which this
 * controller and the desktop's share, so a check tightened on one chain
 * cannot be forgotten on the other.
 *
 * <p><b>Signing is in none of it.</b> The website never downloads a document
 * in order to sign it and never submits a signature: the certificate stays on
 * the signer's smart card and the desktop application does the signing. The
 * one download here is of a document that is already finished.
 */
@RestController
@RequestMapping("/web/api/workflows")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebWorkflowController {

    private final RemoteWorkflowService workflows;

    private final WorkflowOperations operations;

    /** Phase B's launch codes, issued here for a website session. */
    private final LaunchCodeService launches;

    public WebWorkflowController(RemoteWorkflowService workflows,
                                 WorkflowOperations operations,
                                 LaunchCodeService launches) {
        this.workflows = workflows;
        this.operations = operations;
        this.launches = launches;
    }

    /** Workflows this account created — the dashboard's "sent" side. */
    @GetMapping("/owned")
    public List<WorkflowView> owned(Authentication caller) {
        return this.workflows.ownedBy(caller.getName());
    }

    /**
     * Workflows this account is a signer of, in every lifecycle state — the
     * dashboard's "action required" and "waiting" groups are both read out of
     * this one answer, because which group a row belongs to depends on the
     * caller's own slot state and not on the workflow's status alone. The
     * desktop's equivalent is {@code GET /api/workflows/signing-tasks}; the
     * name here follows the website plan.
     */
    @GetMapping("/participating")
    public SigningTasksView participating(Authentication caller) {
        return this.workflows.listParticipating(caller.getName());
    }

    /** One workflow, for the status screen. 404 to anyone not in it. */
    @GetMapping("/{workflowId}")
    public WorkflowView get(@PathVariable String workflowId, Authentication caller) {
        return this.workflows.getWorkflow(workflowId, caller.getName());
    }

    /**
     * What happened, in order — the status timeline reads this. It is the
     * server's own audit trail, which is why the screen can state the order
     * of events rather than infer it from the current state.
     */
    @GetMapping("/{workflowId}/audit")
    public List<AuditView> audit(@PathVariable String workflowId, Authentication caller) {
        return this.workflows.getAudit(workflowId, caller.getName());
    }

    /**
     * The revision chain: hashes and sizes, not bytes. Reading this tells a
     * participant how far the document has got; it hands out no PDF.
     */
    @GetMapping("/{workflowId}/revisions")
    public List<RevisionView> revisions(@PathVariable String workflowId,
                                        Authentication caller) {
        return this.workflows.getRevisions(workflowId, caller.getName());
    }

    /* ------------------------- part 2: the write side ------------------ */

    /**
     * Starts a workflow: the document as one multipart part, the signers and
     * policy as the other. Slots given as e-mail addresses are invited; the
     * links that went out are in the answer and nowhere else, because only
     * their hash is kept.
     *
     * <p>An account that has not confirmed its own address cannot get this
     * far — {@code createWorkflow} carries the Phase D gate in the service,
     * so it holds here without a line of its own.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreateWorkflowResponse> create(
            @RequestPart("request") CreateWorkflowRequest request,
            @RequestPart("file") MultipartFile file,
            Authentication caller) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(this.operations.create(
                request, this.operations.requirePdf(file), caller.getName()));
    }

    /** Invites one signer slot of a workflow this account owns. */
    @PostMapping("/{workflowId}/invitations")
    public ResponseEntity<InvitationView> invite(
            @PathVariable String workflowId,
            @RequestBody CreateInvitationRequest request,
            Authentication caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(this.operations.invite(workflowId, request, caller.getName()));
    }

    /**
     * Where each invitation stands, slot by slot — what the owner's tracking
     * view reads. Statuses and timestamps only: an invitation token appears
     * in the answer of the call that sends it and never again.
     */
    @GetMapping("/{workflowId}/invitations")
    public List<InvitationStatusView> invitations(@PathVariable String workflowId,
                                                  Authentication caller) {
        return this.operations.invitationStatus(workflowId, caller.getName());
    }

    /**
     * Cancels a workflow — the creator or the current signer, and only before
     * it completes. Which of those the caller is, and whether the state still
     * allows it, is the service's decision, not this route's.
     */
    @PostMapping("/{workflowId}/cancel")
    public WorkflowView cancel(@PathVariable String workflowId, Authentication caller) {
        return this.workflows.cancelWorkflow(workflowId, caller.getName());
    }

    /* ------------------- Phase F: the hand-off to the desktop ---------- */

    /**
     * A single-use link that opens SignFlow on this person's computer at this
     * one signing task (Phase F; the launch codes themselves are Phase B).
     *
     * <p>Why the website needs a route of its own for something Phase B
     * already exposed at {@code POST /api/workflows/{id}/launch-codes}: that
     * one reads a bearer token, and this chain has no bearer token to read —
     * it has a session cookie and a CSRF token, and the two chains do not
     * parse each other's credentials (Phase C §3). The service underneath is
     * the same call, so every rule it applies is applied here by
     * construction: the caller must hold a slot of this workflow, the slot
     * must be able to sign now, its certificate must already be bound (the
     * Phase B review's H2 — a launch never enrols a certificate), the
     * address must be verified, and at most five codes a minute.
     *
     * <p><b>{@code Cache-Control: no-store}</b>, because the answer contains
     * the code itself. It is the credential that opens a signing session: two
     * minutes, one use, and no copy of it is kept server-side beyond its
     * hash.
     */
    @PostMapping("/{workflowId}/launch-codes")
    public ResponseEntity<LaunchCodeIssued> launchCode(@PathVariable String workflowId,
                                                       Authentication caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(this.launches.issue(caller.getName(), workflowId));
    }

    /* --------------------- part 3: the finished document --------------- */

    /**
     * The signed document, once everyone has signed it (gap G6: until now,
     * only the signer whose turn it was could download anything, so the
     * person who sent a contract out could never get the finished copy
     * back).
     *
     * <p>Everyone who took part may read it — the creator and every signer —
     * and nobody else, by the uniform 404 the service applies. Before the
     * last signature it answers 409: a half-signed revision is a working
     * copy, not the agreement.
     *
     * <p>This is the only place in Phase E where document bytes leave the
     * server, and it is still not a signing route: what it hands over is
     * finished and cannot be submitted back.
     */
    @GetMapping("/{workflowId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String workflowId,
                                           Authentication caller) {
        String user = caller.getName();
        byte[] pdf = this.workflows.getCompletedDocument(workflowId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, WorkflowOperations.attachment(
                        this.workflows.getWorkflow(workflowId, user).documentName()))
                .body(pdf);
    }
}
