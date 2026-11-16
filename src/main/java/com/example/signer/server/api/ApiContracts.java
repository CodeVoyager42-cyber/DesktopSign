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

    /**
     * Response of POST /api/workflows: the created workflow.
     *
     * <p>It used to carry the invitation links that had just been e-mailed to
     * the workflow's address-shaped signer slots, present only here because a
     * later read could not reproduce them. There are no invitations and no
     * address-shaped slots any more — every slot names an account that
     * already exists — so the list is gone rather than left empty.
     */
    public record CreateWorkflowResponse(WorkflowView workflow) {
    }

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
