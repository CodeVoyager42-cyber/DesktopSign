package com.example.signer.server.invites;

import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bridges workflow creation and the invitation system (brief §53): signer
 * slots specified by EMAIL are bound to that address — reusing an existing
 * account for the address, or creating a passwordless {@code PENDING_INVITE}
 * invitation identity that can only become an account through its invitation
 * — and then receive an emailed invitation; slots specified by a plain
 * username must already exist as accounts (today's direct-assignment
 * behaviour).
 *
 * <p>Active only in database mode; the file-based mode has no account or
 * email concept and is untouched.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class WorkflowInvitationCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowInvitationCoordinator.class);

    /** A slot that will receive an email invitation once the workflow exists. */
    public record SlotInvite(int signerOrder, String email, String username) {
    }

    private final UserRepository users;
    private final InvitationService invitations;

    public WorkflowInvitationCoordinator(UserRepository users,
                                         InvitationService invitations) {
        this.users = users;
        this.invitations = invitations;
    }

    /**
     * Validates all signer specs and, for email slots, creates or reuses the
     * invited placeholder account.
     *
     * @return the (possibly rewritten) request plus the slots to invite
     */
    public Preparation prepare(CreateWorkflowRequest request, String creator) {
        if (request == null || request.signers() == null
                || request.signers().isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "A workflow needs at least one signer");
        }
        boolean changed = false;
        List<SlotInvite> pendingInvites = new ArrayList<>();
        List<CreateWorkflowRequest.SignerSpec> rewritten = new ArrayList<>();

        for (CreateWorkflowRequest.SignerSpec spec : request.signers()) {
            String userId = spec.userId() == null ? "" : spec.userId().trim();
            if (userId.contains("@")) {
                String email = UserEntity.normalizeEmail(userId);
                if (!email.matches(".+@.+\\..+")) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST,
                            "'" + userId + "' is not a valid email address");
                }
                // One email = one identity. An account that already exists for
                // that address (registered or invitation placeholder) is
                // reused, so a slot can never be bound to a different
                // person's account and a second row for the same email can
                // never be created.
                String username = ensureInvitedAccount(email);
                rewritten.add(new CreateWorkflowRequest.SignerSpec(
                        spec.order(), username, spec.certificateSha256()));
                changed = true;
                pendingInvites.add(new SlotInvite(spec.order(), email, username));
            } else {
                if (!users.existsByUsername(userId)) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST,
                            "Unknown workflow user '" + userId + "' — invite"
                                    + " them by email address instead");
                }
                rewritten.add(spec);
            }
        }
        CreateWorkflowRequest effective = changed
                ? new CreateWorkflowRequest(request.documentName(),
                        request.signingPolicy(), rewritten)
                : request;
        return new Preparation(effective, pendingInvites);
    }

    /**
     * Sends the invitation emails once the workflow exists.
     *
     * @return the exact links that were emailed, in slot order, so the
     *         sender can see what went out. They exist only in this return
     *         value: the database stores token hashes, never tokens, so a
     *         later read cannot reproduce them.
     */
    public List<InvitationLinkView> invite(String workflowPublicId,
                                          List<SlotInvite> slotInvites,
                                          String invitedBy) {
        List<InvitationLinkView> sent = new ArrayList<>();
        for (SlotInvite invite : slotInvites) {
            String rawToken = invitations.invite(workflowPublicId,
                    invite.signerOrder(), invite.email(), invitedBy);
            sent.add(new InvitationLinkView(invite.signerOrder(),
                    invite.email(), invitations.invitationUrl(rawToken)));
        }
        return sent;
    }

    /**
     * Resolves the identity an invitation is bound to, creating it only when
     * the address has never been seen.
     *
     * <p>The row is NOT an account: it carries no password, has status
     * {@code PENDING_INVITE} and can never authenticate. It exists because a
     * signer slot must reference an identity (foreign key) before the
     * recipient registers — the invitation is the only way that identity
     * ever becomes a real, authenticated account (brief §16). Registration
     * activates it; registration is not signing.
     *
     * @return the username of the identity for {@code email}
     */
    private String ensureInvitedAccount(String email) {
        // Already known address (registered account or earlier invitation):
        // reuse it, whatever its username happens to be, so the slot always
        // points at the person the invitation is addressed to.
        var byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            return byEmail.get().getUsername();
        }
        String username = uniqueInvitedUsername(email);
        UserEntity placeholder = new UserEntity();
        placeholder.setUsername(username);
        placeholder.setEmail(email);
        placeholder.setDisplayName(capitalize(username));
        placeholder.setStatus("PENDING_INVITE");
        users.save(placeholder);
        log.info("Invitation identity prepared for {} (username {}, not registered)",
                email, username);
        return username;
    }

    /**
     * Username for a newly invited address: the email's local part, suffixed
     * when that id is already taken by someone else. Never steals an existing
     * account's id and never fails on a collision.
     */
    private String uniqueInvitedUsername(String email) {
        String local = email.substring(0, email.indexOf('@'))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        if (local.isBlank() || local.chars().allMatch(c -> c == '_' || c == '.')) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The email '" + email + "' cannot be turned into a user id");
        }
        String candidate = local;
        for (int suffix = 2; suffix <= 100; suffix++) {
            if (!users.existsByUsername(candidate)) {
                return candidate;
            }
            candidate = local + "-" + suffix;
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST,
                "Too many invited addresses share the id '" + local + "' — use a"
                        + " different address");
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The rewritten request plus the slots awaiting invitation emails. */
    public record Preparation(CreateWorkflowRequest request,
                              List<SlotInvite> pendingInvites) {
    }
}
