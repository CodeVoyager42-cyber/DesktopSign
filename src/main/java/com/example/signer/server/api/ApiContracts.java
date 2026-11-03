package com.example.signer.server.api;

import java.util.List;

/**
 * JSON contracts shared by the coordination API and the desktop client.
 * Kept as plain records so both sides serialize identically.
 *
 * <p>A workflow has exactly one signer — the account that uploaded the
 * document — so nothing here names a recipient, an order to sign in, or a
 * turn to take.
 */
public final class ApiContracts {

    private ApiContracts() {
    }

    /**
     * Request body of POST /api/workflows.
     *
     * <p>{@code certificateSha256} is optional: {@code null} leaves the
     * signer slot UNBOUND — the signer then registers their own certificate
     * from the machine their token is plugged into (no document is stored
     * until the slot is bound).
     */
    public record CreateWorkflowRequest(
            String documentName,
            String certificateSha256) {

        /** The common case: register the certificate when signing. */
        public CreateWorkflowRequest(String documentName) {
            this(documentName, null);
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
            String currentSignerUserId,
            String currentSignerFingerprintShort,
            String blockedReason,
            boolean awaitingYourCertificate) {

        /** Always 1: one document, one signer. */
        public int totalSignatures() {
            return 1;
        }
    }

    /** Response of GET /api/workflows/{id}/task. */
    public record TaskView(
            String workflowId,
            String documentName,
            String status,
            Integer signerOrder,
            String signatureField,
            int documentRevision,
            String documentSha256,
            List<StepView> signers) {

        /**
         * The signer slot as the task view renders it.
         *
         * <p>{@code signed} is the ONLY authoritative signature state: it is
         * true exclusively after a server-validated signature submission was
         * accepted. {@code signerState} is the explicit lifecycle of the
         * slot, derived server-side (never from client input):
         *
         * <ul>
         *   <li>{@code PENDING} — nothing has been signed yet;</li>
         *   <li>{@code SIGNED} — an accepted signature exists. Only a
         *       validated submission reaches this state.</li>
         * </ul>
         *
         * <p>{@code bound} is about the certificate registration step only;
         * it is never a signature statement.
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
     * The documents workspace
     * ------------------------------------------------------------------ */

    /**
     * Response of GET /api/workflows/signing-tasks: every document of the
     * signed-in user, across all lifecycle states — unlike {@code my-tasks},
     * which returns only actionable items. Dates are ISO-8601 instant
     * strings when known.
     */
    public record SigningTasksView(List<TaskItemView> tasks) {
    }

    /**
     * One document as its owner sees it. The raw backend state names stay
     * here; the clients map them to human labels — the server never
     * hard-codes presentation strings.
     */
    public record TaskItemView(
            String workflowId,
            String documentName,
            /** Authenticated id of the owner, who is also the signer. */
            String senderId,
            /** Human display name of the owner, resolved server-side. */
            String senderDisplayName,
            /** Raw WorkflowState name of the workflow. */
            String status,
            String createdAt,
            String completedAt,
            ParticipantStateView myState,
            /** Time of the workflow's latest event (ISO-8601), or null. */
            String updatedAt) {
    }

    /**
     * The signed-in user's slot within a workflow.
     * {@code myStateLabel} raw values: YOUR_TURN, IN_PROGRESS, SIGNED,
     * CERTIFICATE_PENDING, BLOCKED, CANCELLED.
     *
     * <p>{@code SIGNED} is emitted ONLY when the slot carries an accepted
     * signature ({@code signed} = true). Navigation and account state never
     * imply a signature.
     */
    public record ParticipantStateView(
            String myStateLabel,
            Integer participantOrder,
            boolean signed,
            boolean waitingForMe) {

        public ParticipantStateView(ApiStates.ParticipantState state, Integer participantOrder,
                                    boolean signed, boolean waitingForMe) {
            this(state.name(), participantOrder, signed, waitingForMe);
        }

        /** {@link #myStateLabel()} as an enum (UNKNOWN when unrecognised). */
        public ApiStates.ParticipantState state() {
            return ApiStates.ParticipantState.parse(myStateLabel);
        }
    }
}
