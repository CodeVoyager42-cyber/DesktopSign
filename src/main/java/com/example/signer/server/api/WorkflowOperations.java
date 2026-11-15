package com.example.signer.server.api;

import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.workflow.RemoteWorkflowService;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

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
 * the service.
 */
@Component
public class WorkflowOperations {

    private final RemoteWorkflowService workflows;

    public WorkflowOperations(RemoteWorkflowService workflows) {
        this.workflows = workflows;
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
     * Starts a workflow. Every signer slot names an account that already
     * exists.
     *
     * <p>A slot used to be allowed to name an e-mail address instead, which
     * {@code WorkflowInvitationCoordinator} turned into a passwordless
     * invited identity and then e-mailed. That whole path is gone, so the
     * only remaining way into a signer slot is a username — and a slot that
     * still carries an address is <b>refused</b> by
     * {@link RemoteWorkflowService}, never quietly accepted as a username
     * that happens to contain an {@code @}. Accepting one would create a
     * workflow whose signer can never be found, which is the kind of silent
     * failure this removal exists to end.
     *
     * <p>The response's invitation-link list is always empty now. It stays in
     * the contract because both clients still read the field; it is emptied
     * here rather than removed under them.
     */
    public CreateWorkflowResponse create(CreateWorkflowRequest request, byte[] pdf,
                                         String caller) {
        return new CreateWorkflowResponse(
                this.workflows.createWorkflow(request, pdf, caller), List.of());
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
