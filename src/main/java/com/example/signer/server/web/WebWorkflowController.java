package com.example.signer.server.web;

import com.example.signer.server.api.ApiContracts.AuditView;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SigningTasksView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.workflow.RemoteWorkflowService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What the website may <b>read</b> about workflows (Phase E, part 1 of 3).
 * Five routes, each one line over {@link RemoteWorkflowService} — the same
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
 * <p><b>Read-only.</b> Creating a workflow, inviting, cancelling and the
 * final-PDF download are parts 2 and 3. Signing is in neither: the website
 * never downloads a document to sign and never submits a signature — the
 * certificate stays on the signer's smart card and the desktop application
 * does the signing.
 */
@RestController
@RequestMapping("/web/api/workflows")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebWorkflowController {

    private final RemoteWorkflowService workflows;

    public WebWorkflowController(RemoteWorkflowService workflows) {
        this.workflows = workflows;
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
}
