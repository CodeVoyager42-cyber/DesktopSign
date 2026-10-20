package com.example.signer.server.api;

import java.util.List;

/**
 * JSON contracts shared by the coordination API and the desktop client.
 * Kept as plain records so both sides serialize identically.
 */
public final class ApiContracts {

    private ApiContracts() {
    }

    /** Request body of POST /api/workflows. */
    public record CreateWorkflowRequest(
            String documentName,
            String signingPolicy,
            List<SignerSpec> signers) {

        /** Legacy shape: sequential policy (policy parsed from null). */
        public CreateWorkflowRequest(String documentName, List<SignerSpec> signers) {
            this(documentName, null, signers);
        }

        /**
         * One signer slot. {@code certificateSha256} is optional: {@code null}
         * leaves the slot UNBOUND — the signer then binds their own
         * certificate from their own machine before the workflow is prepared
         * (no document is stored until every slot is bound).
         */
        public record SignerSpec(int order, String userId, String certificateSha256) {
        }
    }

    /** Request body of POST /api/workflows/{id}/signers/{userId}/certificate. */
    public record BindCertificateRequest(String certificateSha256) {
    }

    /** Response of GET /api/workflows/{id} and of my-tasks items. */
    public record WorkflowView(
            String workflowId,
            String documentName,
            String status,
            int currentRevision,
            int completedSignatures,
            int totalSignatures,
            String currentSignerUserId,
            String currentSignerFingerprintShort,
            String blockedReason,
            String signingPolicy,
            boolean waitingForClaim,
            boolean awaitingYourCertificate) {
    }

    /**
     * Response of GET /api/workflows/{id}/task. {@code signerOrder} is null
     * while a FLEXIBLE turn is unclaimed ({@code waitingForClaim} = true);
     * {@code eligibleSignerUserIds} then lists who may claim.
     */
    public record TaskView(
            String workflowId,
            String documentName,
            String status,
            Integer signerOrder,
            String signatureField,
            int documentRevision,
            String documentSha256,
            boolean waitingForClaim,
            List<String> eligibleSignerUserIds,
            List<StepView> signers) {

        /**
         * One signer slot as the task view renders it.
         *
         * <p>{@code signed} is the ONLY authoritative signature state: it is
         * true exclusively after a server-validated signature submission was
         * accepted for this slot. {@code signerState} is the explicit
         * lifecycle of the slot, derived server-side (never from client
         * input):
         *
         * <ul>
         *   <li>{@code INVITED} — the slot belongs to an invited address that
         *       has no registered account yet: invitation exists, recipient
         *       not registered, nothing signed;</li>
         *   <li>{@code PENDING} — the recipient has a registered account but
         *       has NOT signed;</li>
         *   <li>{@code SIGNED} — an accepted signature exists for this slot.
         *       Only a validated submission reaches this state.</li>
         * </ul>
         *
         * <p>{@code bound} is about the certificate binding step only; it is
         * never a signature statement.
         */
        public record StepView(int order, String userId, boolean signed,
                               boolean yourTurn, boolean bound,
                               String signerState) {
        }
    }

    /** Request body of POST /api/workflows/{id}/signatures. */
    public record SignatureSubmissionRequest(
            int parentRevision,
            String parentSha256,
            String signingOperationId) {
    }

    /** Response of POST /api/workflows/{id}/signatures. */
    public record SignatureSubmissionResponse(
            String workflowId,
            int acceptedRevision,
            String status,
            String nextSignerUserId,
            boolean completed) {
    }

    /** One revision entry of GET /api/workflows/{id}/revisions. */
    public record RevisionView(
            int revisionNumber,
            int parentRevision,
            String sha256,
            long sizeBytes,
            String createdAt,
            String createdBy) {
    }

    /** One audit entry of GET /api/workflows/{id}/audit. */
    public record AuditView(
            long sequence,
            String eventType,
            String actor,
            String detail,
            String occurredAt) {
    }

    /** Machine-readable error body for every non-2xx API response. */
    public record ApiError(String code, String message) {
    }

    /* ------------------------------------------------------------------
     * Authentication contracts (JWT-based, replace HTTP Basic)
     * ------------------------------------------------------------------ */

    /** Request body of POST /api/auth/login. */
    public record LoginRequest(String username, String password) {
    }

    /** Response of POST /api/auth/login and POST /api/auth/refresh. */
    public record TokenPairResponse(
            String accessToken,
            long expiresIn,
            String refreshToken) {
    }

    /** Response of GET /api/auth/me. */
    public record MeResponse(String userId) {
    }

    /* ------------------------------------------------------------------
     * Invitations (brief Phase 4/6)
     * ------------------------------------------------------------------ */

    /** Request body of POST /api/workflows/{id}/invitations. */
    public record CreateInvitationRequest(int signerOrder, String email) {
    }

    /** View of an invitation. Never contains the raw token. */
    public record InvitationView(
            Long invitationId,
            String workflowId,
            String documentName,
            int signerOrder,
            String email,
            String invitedBy,
            String status,
            String expiresAt,
            /**
             * The exact link that was just emailed, returned ONCE at send
             * time so the sender can see what went out. It is never stored
             * and never comes back from a read endpoint — the database keeps
             * only the token hash.
             */
            String invitationUrl) {
    }

    /**
     * One emailed invitation link, as surfaced to the sender. Mirrors
     * {@link InvitationView#invitationUrl()} for the workflow-create flow,
     * where several slots are invited at once.
     */
    public record InvitationLinkView(
            int signerOrder,
            String email,
            String invitationUrl) {
    }

    /**
     * Response of POST /api/workflows: the created workflow plus the links
     * that were emailed to its email-addressed signer slots. The links are
     * present only in this response — a later read cannot reproduce them.
     */
    public record CreateWorkflowResponse(
            WorkflowView workflow,
            List<InvitationLinkView> invitations) {
    }

    /**
     * Response of GET /api/server-info/invitation-link: the base URL that
     * emailed links use, so the sender can see where recipients are sent.
     *
     * @param localOnly true when the base URL resolves only on this machine
     *                  (a loopback address), i.e. remote signers cannot open
     *                  the link until the server is given a public address
     */
    public record InvitationLinkInfo(
            String baseUrl,
            boolean localOnly,
            String source) {
    }

    /** Request body of POST /api/auth/register (invitation-bound). */
    public record RegisterRequest(
            String invitationToken,
            String displayName,
            String password) {
    }

    /**
     * Owner-facing status of one signer slot's invitation (workflow tracking).
     * Dates are ISO-8601 instant strings; {@code slotSigned} mirrors the
     * workflow signer slot so completed signatures stay visible even after
     * the invitation row was superseded. Never contains the raw token.
     */
    public record InvitationStatusView(
            Long invitationId,
            int signerOrder,
            String email,
            String status,
            /** PENDING | SENT | FAILED | null (row predates tracking). */
            String emailStatus,
            String createdAt,
            String expiresAt,
            String acceptedAt,
            boolean slotSigned) {

        /** {@link #status()} as an enum (UNKNOWN when unrecognised). */
        public ApiStates.InvitationStatus invitationStatus() {
            return ApiStates.InvitationStatus.parse(status);
        }

        /** {@link #emailStatus()} as an enum; null when the row predates tracking. */
        public ApiStates.EmailStatus emailState() {
            return emailStatus == null ? null : ApiStates.EmailStatus.parse(emailStatus);
        }
    }

    /**
     * Result of claiming an invitation (POST /api/invitations/{token}/claim):
     * the participant-scoped context the desktop opens the signing workflow
     * with. Deliberately narrow — one workflow, one participant slot, one
     * document; never a general application session (brief §21/§46).
     */
    /**
     * A guest signing session opened from an invitation link (no account).
     * {@code accessToken} authorizes ONLY this signer slot's signing task;
     * it is not a login and expires with the invitation at the latest.
     * {@code signerId} is the slot's signer id as it appears in the task.
     */
    /* ---------------- Phase B: website → desktop launch codes ---------------- */

    /**
     * Answer of POST /api/workflows/{id}/launch-codes: the link to open
     * (contains the one-time code) and how many seconds it stays valid.
     */
    public record LaunchCodeIssued(String deepLink, long expiresInSeconds) {
        @Override
        public String toString() {
            return "LaunchCodeIssued[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    /** Body of POST /api/desktop/launch. */
    public record LaunchExchangeRequest(String code, String appVersion, String device) {
        @Override
        public String toString() {
            return "LaunchExchangeRequest[appVersion=" + appVersion + "]";
        }
    }

    /** Answer of POST /api/desktop/launch: a task session for ONE signing task. */
    public record LaunchSessionView(String accessToken, String tokenType, long expiresIn,
                                    String workflowId, int signerOrder, String signerId,
                                    String documentName, String minDesktopVersion) {
        @Override
        public String toString() {
            return "LaunchSessionView[workflowId=" + workflowId + ", signerOrder=" + signerOrder + "]";
        }
    }

    /** One of the user's own launches, for the "opened on device X" notice. */
    public record LaunchView(long id, String workflowId, int signerOrder, String state,
                             String createdAt, String expiresAt, String consumedAt,
                             String appVersion, String device) {
    }

    /** GET /api/health: reachability only, nothing sensitive. */
    public record HealthView(String status, int apiVersion, String minDesktopVersion) {
    }

    /** Body of POST /api/invitations/{token}/guest-session (security Phase 3). */
    public record GuestSessionRequest(String code) {
    }

    /**
     * Answer of POST /api/invitations/{token}/guest-code: where the code went
     * (masked) and when it expires / when a new one may be requested.
     */
    public record GuestCodeView(String sentTo, long expiresInSeconds,
                                long resendAfterSeconds) {
    }

    public record GuestSessionView(
            String accessToken,
            String tokenType,
            long expiresIn,
            String workflowId,
            String documentName,
            String requestedBy,
            int signerOrder,
            String signerId,
            String email,
            String invitationExpiresAt) {
    }

    public record SigningSessionView(
            String workflowId,
            String documentName,
            int participantOrder,
            String recipientEmail,
            /** When this signing authorization expires (= invitation expiry). */
            String expiresAt,
            boolean alreadyClaimed) {
    }

    /** Public preview of an invitation (GET /api/invitations/{token}). */
    public record InvitationPreviewView(
            String workflowId,
            String documentName,
            String invitedBy,
            String invitedEmail,
            String status,
            String expiresAt,
            boolean accountExists) {
    }

    /* ------------------------------------------------------------------
     * Signing Tasks workspace (participant-scoped task list)
     * ------------------------------------------------------------------ */

    /**
     * Response of GET /api/workflows/signing-tasks: every workflow the
     * signed-in user PARTICIPATES in as a signer, across all lifecycle
     * states — unlike {@code my-tasks}, which returns only actionable
     * items. Dates are ISO-8601 instant strings when known.
     */
    public record SigningTasksView(List<TaskItemView> tasks) {
    }

    /**
     * One signing task as seen by one participant. The raw backend state
     * names stay here; the desktop client maps them to human labels — the
     * server never hard-codes presentation strings.
     */
    public record TaskItemView(
            String workflowId,
            String documentName,
            /** Authenticated id of the workflow creator (may be null). */
            String senderId,
            /** Human display name of the creator, resolved server-side. */
            String senderDisplayName,
            /** Raw WorkflowState name of the workflow. */
            String status,
            String createdAt,
            String completedAt,
            ParticipantStateView myState,
            int totalSignatures,
            int completedSignatures,
            /** SEQUENTIAL (fixed order) or FLEXIBLE (any order, one at a time). */
            String signingPolicy,
            /** Every signer slot in order, with its state (names only, no emails). */
            List<TaskParticipantView> participants,
            /** When the caller's own invitation expires (ISO-8601), or null. */
            String expiresAt,
            /** Time of the workflow's latest event (ISO-8601), or null. */
            String updatedAt) {

        /** Pre-redesign shape (older servers / tests): new fields unknown. */
        public TaskItemView(String workflowId, String documentName, String senderId,
                            String senderDisplayName, String status, String createdAt,
                            String completedAt, ParticipantStateView myState,
                            int totalSignatures, int completedSignatures) {
            this(workflowId, documentName, senderId, senderDisplayName, status, createdAt,
                    completedAt, myState, totalSignatures, completedSignatures,
                    null, null, null, null);
        }
    }

    /**
     * One signer slot as shown in a task's signing order.
     * {@code state}: SIGNED, CURRENT (holds the turn now) or WAITING.
     */
    public record TaskParticipantView(int order, String name, boolean you, String state) {

        public TaskParticipantView(int order, String name, boolean you, ApiStates.SlotState state) {
            this(order, name, you, state.name());
        }

        /** {@link #state()} as an enum (UNKNOWN when unrecognised). */
        public ApiStates.SlotState slotState() {
            return ApiStates.SlotState.parse(state);
        }
    }

    /**
     * The signed-in user's slot within a workflow.
     * {@code myStateLabel} raw values: YOUR_TURN, WAITING_FOR_CLAIM,
     * WAITING_FOR_OTHERS, IN_PROGRESS, SIGNED, INVITED, CERTIFICATE_PENDING,
     * BLOCKED, CANCELLED — later overlaid with REVOKED / DECLINED /
     * EXPIRED from the latest invitation for the slot (database mode).
     *
     * <p>{@code SIGNED} is emitted ONLY for the participant whose own slot
     * carries an accepted signature ({@code signed} = true). A completed
     * workflow never makes an unsigned participant look signed: account
     * existence, invitation state and navigation never imply a signature.
     * {@code INVITED} means an invitation is pending for a recipient who has
     * not registered yet.
     */
    public record ParticipantStateView(
            String myStateLabel,
            Integer participantOrder,
            boolean signed,
            boolean waitingForMe,
            boolean waitingForClaim) {

        public ParticipantStateView(ApiStates.ParticipantState state, Integer participantOrder,
                                    boolean signed, boolean waitingForMe, boolean waitingForClaim) {
            this(state.name(), participantOrder, signed, waitingForMe, waitingForClaim);
        }

        /** {@link #myStateLabel()} as an enum (UNKNOWN when unrecognised). */
        public ApiStates.ParticipantState state() {
            return ApiStates.ParticipantState.parse(myStateLabel);
        }
    }
}
