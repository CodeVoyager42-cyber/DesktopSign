package com.example.signer.server.invites;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.DocumentFileStorage;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.entity.WorkflowEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Public signing page for emailed invitations: {@code GET /sign/{token}}.
 *
 * <p>The token in the URL is the whole capability — the database stores only
 * its SHA-256 hash, so nothing here exposes ids or credentials. The page
 * shows the request and hands the signer to the desktop application (where the PKCS#11 signature is actually produced).
 * Expired, already-used, revoked and unknown tokens all render a friendly
 * page instead of an error.
 */
@RestController
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class SignPageController {

    private static final Logger log =
            LoggerFactory.getLogger(SignPageController.class);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy HH:mm", Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());

    private final InvitationService invitations;
    private final WorkflowRepository workflows;
    private final UserRepository users;
    private final DocumentFileStorage storage;
    private final String appName;
    private final String downloadUrl;

    public SignPageController(InvitationService invitations,
                             WorkflowRepository workflows,
                             UserRepository users,
                             DocumentFileStorage storage,
                             @Value("${app.name:SignFlow}") String appName,
                             @Value("${signature.remote-server.invitation.download-url:${SIGNER_DOWNLOAD_URL:}}")
                             String downloadUrl) {
        this.invitations = invitations;
        this.workflows = workflows;
        this.users = users;
        this.storage = storage;
        this.appName = appName;
        this.downloadUrl = downloadUrl;
    }

    /**
     * Canonical HTTPS invitation entry point (brief §9): validates the
     * invitation, shows the request, and hands off to the desktop app. The
     * browser never becomes the signing environment.
     */
    @GetMapping(value = "/invitations/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        try {
            InvitationEntity invitation;
            try {
                invitation = invitations.redeemableByToken(token);
            } catch (ApiException e) {
                if (e.code() != ApiErrorCode.INVITATION_ALREADY_USED) {
                    throw e;
                }
                if (invitations.isSlotSignedForToken(token)) {
                    throw new ApiException(ApiErrorCode.INVITATION_COMPLETED,
                            "Your signature for this invitation is already recorded");
                }
                // Opened before, not signed yet: the link still works.
                invitation = invitations.reopenableByToken(token);
            }
            WorkflowEntity wf = workflows.findById(invitation.getWorkflowId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found"));
            // Registration state is a property of the INVITED ADDRESS, not of
            // whoever occupies the signer slot: an invited stranger must see
            // the registration flow, never "you already have an account".
            boolean accountExists = users.findByEmail(invitation.getEmail())
                    .map(UserEntity::isActive)
                    .orElse(false);
            log.info("Invitation page opened for workflow {} signer {}"
                            + " (recipient {}, registered={})", wf.getWorkflowId(),
                    invitation.getSignerOrder(), invitation.getEmail(),
                    accountExists);
            String html = SignPageRenderer.pending(new SignPageRenderer.View(
                    wf.getDocumentName(), wf.getCreatedBy(), invitation.getEmail(),
                    DATE.format(invitation.getExpiresAt()),
                    invitations.deepLink(token),
                    null, // no bearer-token PDF download: review happens in the app
                    appName, accountExists, downloadUrl));
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (ApiException e) {
            return noticeFor(e);
        }
    }

    /**
     * Legacy link kept for emails already sent: forwards to the canonical
     * {@code /invitations/{token}} entry point.
     */
    @GetMapping("/sign/{token}")
    public ResponseEntity<Void> legacy(@PathVariable String token) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/invitations/" + token))
                .build();
    }

    /**
     * Former document download for bearer-token holders (P0.10).
     *
     * <p>Anyone holding a forwarded or leaked invitation link could download
     * the latest signed revision here without authenticating. The intended
     * flow does not need it: the invitation opens the desktop app, the
     * signer signs in (or registers), and the document is fetched through
     * the authenticated workflow API ({@code GET /api/workflows/{id}/document}).
     * The route stays so pages rendered for already-sent emails show a
     * helpful notice instead of a 404 — it never returns the PDF.
     */
    @GetMapping("/sign/{token}/document")
    public ResponseEntity<byte[]> document(@PathVariable String token) {
        return htmlError(HttpStatus.GONE, "Open the document in " + appName,
                "For your security the document is no longer downloadable from an"
                        + " invitation link. Open the invitation in " + appName
                        + ": the document is shown there before you sign (no account needed).");
    }

    private ResponseEntity<byte[]> htmlError(HttpStatus status, String heading,
                                            String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(SignPageRenderer.notice(heading, detail, appName)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<String> noticeFor(ApiException e) {
        return ResponseEntity.status(statusFor(e))
                .contentType(MediaType.TEXT_HTML)
                .body(SignPageRenderer.notice(headingFor(e), detailFor(e), appName));
    }

    private HttpStatus statusFor(ApiException e) {
        return switch (e.code()) {
            case INVITATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVITATION_ALREADY_USED, INVITATION_COMPLETED -> HttpStatus.OK;
            default -> HttpStatus.GONE;
        };
    }

    private String headingFor(ApiException e) {
        return switch (e.code()) {
            case INVITATION_NOT_FOUND -> "Link not valid";
            case INVITATION_EXPIRED -> "Link expired";
            case INVITATION_REVOKED -> "Link withdrawn";
            case INVITATION_ALREADY_USED -> "Already used";
            case INVITATION_COMPLETED -> "Already signed";
            default -> "Something went wrong";
        };
    }

    private String detailFor(ApiException e) {
        return switch (e.code()) {
            case INVITATION_NOT_FOUND -> "This invitation link is not recognised. It may"
                    + " have been mistyped, or it was never valid.";
            case INVITATION_EXPIRED -> "This invitation link has expired. Ask the sender"
                    + " to invite you again to get a fresh link.";
            case INVITATION_REVOKED -> "This invitation was withdrawn by the sender.";
            case INVITATION_ALREADY_USED -> "This invitation has already been used."
                    + " If you still need to sign, ask the requester for a new invitation.";
            case INVITATION_COMPLETED -> "Your signature for this document is already"
                    + " recorded. Nothing else to do.";
            default -> String.valueOf(e.getMessage());
        };
    }
}