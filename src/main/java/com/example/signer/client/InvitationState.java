package com.example.signer.client;

import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts.InvitationPreviewView;

/**
 * What the invitation screen shows. Every state has its own icon, title,
 * explanation and action — no single generic "invalid or expired" message.
 * Classification is pure (no UI) so it is unit-tested.
 */
public enum InvitationState {

    VALID("Invitation ready",
            "You're ready to review and sign this document."),
    EXPIRED("This invitation has expired.",
            "Ask the requester to send a new invitation."),
    REVOKED("This invitation has been revoked.",
            "The requester withdrew it, or replaced it with a newer one."
                    + " Check your email for a more recent invitation."),
    ALREADY_USED("This invitation has already been used.",
            "It can't be opened again. If you still need to sign, ask the"
                    + " requester to send a new invitation."),
    COMPLETED("This invitation has already been completed.",
            "Your signature for this document is already recorded. Nothing else to do."),
    INVALID("This invitation could not be verified.",
            "The link may be incomplete or mistyped. Copy the full link from"
                    + " the email and try again."),
    NETWORK_ERROR("We couldn't connect to the SignFlow server.",
            "Check your internet connection, then try again."),
    SERVER_ERROR("The SignFlow server could not open this invitation.",
            "This is a problem on the server side. Try again in a moment.");

    private final String title;
    private final String explanation;

    InvitationState(String title, String explanation) {
        this.title = title;
        this.explanation = explanation;
    }

    public String title() {
        return title;
    }

    public String explanation() {
        return explanation;
    }

    /** True for the states where "Try again" makes sense. */
    public boolean retryable() {
        return this == NETWORK_ERROR || this == SERVER_ERROR;
    }

    /** State of a successfully loaded preview. */
    public static InvitationState of(InvitationPreviewView preview) {
        if (preview == null || preview.status() == null) {
            return INVALID;
        }
        return switch (preview.status()) {
            // ACCEPTED = opened before but not signed yet (the server
            // answers COMPLETED once it is signed): it can be reopened.
            case "PENDING", "ACCEPTED" -> VALID;
            case "EXPIRED" -> EXPIRED;
            case "REVOKED", "DECLINED" -> REVOKED;
            default -> INVALID;
        };
    }

    /** State for a failed preview request. */
    public static InvitationState of(Throwable failure) {
        if (failure instanceof RemoteApiException api) {
            String code = api.serverCode();
            if (code != null) {
                switch (code) {
                    case "INVITATION_EXPIRED":
                        return EXPIRED;
                    case "INVITATION_REVOKED":
                        return REVOKED;
                    case "INVITATION_ALREADY_USED":
                        return ALREADY_USED;
                    case "INVITATION_COMPLETED":
                        return COMPLETED;
                    case "INVITATION_NOT_FOUND", "WORKFLOW_NOT_FOUND", "BAD_REQUEST":
                        return INVALID;
                    default:
                        break;
                }
            }
            if (api.httpStatus() == 404) {
                return INVALID;
            }
            return SERVER_ERROR;
        }
        if (failure instanceof SignerException se
                && WorkflowApiClient.isTransportFailure(se)) {
            return NETWORK_ERROR;
        }
        return SERVER_ERROR;
    }
}
