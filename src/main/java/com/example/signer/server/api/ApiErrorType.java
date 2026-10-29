package com.example.signer.server.api;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.server.workflow.WorkflowConflictException;
import org.springframework.dao.OptimisticLockingFailureException;
import com.example.signer.server.api.ApiContracts.ApiError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Machine-readable API error model: every non-2xx response carries an
 * {@link ApiError} body with a stable {@code code}. Internal details are
 * logged server-side, never returned to the client.
 */
public final class ApiErrorType {

    private ApiErrorType() {
    }

    /** Stable machine-readable error codes (§29 of the task spec). */
    public enum ApiErrorCode {
        WORKFLOW_NOT_FOUND,
        WORKFLOW_NOT_ACCESSIBLE,
        INVALID_WORKFLOW_STATE,
        NOT_YOUR_TURN,
        STALE_REVISION,
        REVISION_CONFLICT,
        SIGNATURE_FIELD_ALREADY_SIGNED,
        CERTIFICATE_REQUIRED,
        CERTIFICATE_ALREADY_BOUND,
        TURN_ALREADY_CLAIMED,
        INVALID_SIGNATURE,
        DOCUMENT_MODIFIED,
        CERTIFICATE_MISMATCH,
        DUPLICATE_OPERATION,
        VALIDATION_FAILED,
        UNAUTHORIZED,
        BAD_REQUEST,
        INVITATION_NOT_FOUND,
        INVITATION_EXPIRED,
        INVITATION_ALREADY_USED,
        INVITATION_REVOKED,
        EMAIL_MISMATCH,
        EMAIL_DELIVERY_FAILED,
        UPLOAD_TOO_LARGE,
        /** No (valid) access token: the client should refresh or sign in. */
        AUTHENTICATION_REQUIRED,
        /**
         * The workflow changed underneath the request (stale copy, version
         * conflict): reload the workflow and decide again.
         */
        WORKFLOW_CONFLICT,
        /**
         * The certificate or signature comes from the development mock token
         * and this server does not run in the {@code dev} profile.
         */
        DEVELOPMENT_IDENTITY_REJECTED,
        /** The invitation was used and its signer slot is already signed. */
        INVITATION_COMPLETED,
        /** A guest session needs the one-time code e-mailed to the invitee. */
        GUEST_VERIFICATION_REQUIRED,
        /** The guest verification code is wrong. */
        GUEST_CODE_INVALID,
        /** No usable code: expired, used up, or too many wrong entries. */
        GUEST_CODE_EXPIRED,
        /**
         * Phase B: a launch code that cannot be used — unknown, malformed,
         * expired, already used, revoked or no longer matching its task. One
         * answer for all of them on purpose.
         */
        LAUNCH_CODE_INVALID,
        /** The desktop app is older than the server accepts. */
        UPGRADE_REQUIRED,
        /** The account's e-mail address is not verified yet. */
        EMAIL_NOT_VERIFIED,
        /** Too many requests of this kind; try again later. */
        TOO_MANY_REQUESTS,
        /**
         * Phase B remediation: the signer's certificate is not registered
         * for this slot yet, so it cannot be opened from a launch link.
         */
        CERTIFICATE_NOT_BOUND
    }

    /** Exception carrying an {@link ApiErrorCode}; thrown by the service layer. */
    public static class ApiException extends RuntimeException {
        private final ApiErrorCode code;

        public ApiException(ApiErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ApiException(ApiErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public ApiErrorCode code() {
            return code;
        }
    }

    /** Maps {@link ApiException} to HTTP responses. */
    @RestControllerAdvice
    public static class Handler {

        private static final Logger log = LoggerFactory.getLogger(Handler.class);

        /** The configured document limit, echoed to the client so it is actionable. */
        @Value("${spring.servlet.multipart.max-file-size:104857600}")
        private DataSize maxUploadSize;

        /**
         * Uploads beyond the configured multipart limits. Tomcat/Spring raise
         * this while parsing the request, i.e. before any controller runs, so
         * it needs its own handler: without one the container error page (no
         * machine-readable body) is all the client sees.
         */
        /**
         * A required multipart part is absent (e.g. a create request without
         * the document). Without this handler the container's default error
         * page is returned — a body without {@code code}/{@code message} —
         * which is useless to the caller.
         */
        @ExceptionHandler(MissingServletRequestPartException.class)
        public ResponseEntity<ApiError> handleMissingPart(
                MissingServletRequestPartException e) {
            log.warn("Rejected request: missing multipart part '{}'",
                    e.getRequestPartName());
            String detail = "file".equals(e.getRequestPartName())
                    ? "The original PDF file is required"
                    : "The " + e.getRequestPartName() + " part is required";
            return ResponseEntity.badRequest()
                    .body(new ApiError(ApiErrorCode.BAD_REQUEST.name(), detail));
        }

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public ResponseEntity<ApiError> handleUploadTooLarge(
                MaxUploadSizeExceededException e) {
            log.warn("Upload rejected: {} (limit {})", e.getMessage(), maxUploadSize);
            String limit = maxUploadSize.toMegabytes() + " MB";
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new ApiError(ApiErrorCode.UPLOAD_TOO_LARGE.name(),
                            "The document is larger than the maximum upload size"
                            + " of " + limit
                            + ". Choose a smaller PDF and try again."));
        }

        @ExceptionHandler(ApiException.class)
        public ResponseEntity<ApiError> handle(ApiException e) {
            HttpStatus status = switch (e.code()) {
                case WORKFLOW_NOT_FOUND -> HttpStatus.NOT_FOUND;          // 404
                case WORKFLOW_NOT_ACCESSIBLE, UNAUTHORIZED -> HttpStatus.FORBIDDEN; // 403
                case NOT_YOUR_TURN, INVALID_WORKFLOW_STATE -> HttpStatus.CONFLICT; // 409
                case STALE_REVISION, REVISION_CONFLICT -> HttpStatus.CONFLICT;      // 409
                case DUPLICATE_OPERATION -> HttpStatus.OK;                // idempotent replay
                case SIGNATURE_FIELD_ALREADY_SIGNED, CERTIFICATE_ALREADY_BOUND,
                     TURN_ALREADY_CLAIMED -> HttpStatus.CONFLICT;
                case CERTIFICATE_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
                case INVALID_SIGNATURE, DOCUMENT_MODIFIED, CERTIFICATE_MISMATCH,
                     VALIDATION_FAILED, DEVELOPMENT_IDENTITY_REJECTED
                        -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
                case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
                case INVITATION_NOT_FOUND -> HttpStatus.NOT_FOUND;     // 404
                case INVITATION_EXPIRED -> HttpStatus.GONE;            // 410
                case INVITATION_ALREADY_USED, INVITATION_REVOKED,
                     INVITATION_COMPLETED -> HttpStatus.CONFLICT; // 409
                case EMAIL_MISMATCH -> HttpStatus.FORBIDDEN;           // 403
                case GUEST_VERIFICATION_REQUIRED, GUEST_CODE_INVALID
                        -> HttpStatus.FORBIDDEN;                       // 403
                case GUEST_CODE_EXPIRED -> HttpStatus.GONE;            // 410
                case LAUNCH_CODE_INVALID -> HttpStatus.BAD_REQUEST;    // 400
                case UPGRADE_REQUIRED -> HttpStatus.UPGRADE_REQUIRED;  // 426
                case EMAIL_NOT_VERIFIED -> HttpStatus.FORBIDDEN;       // 403
                case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS; // 429
                case CERTIFICATE_NOT_BOUND -> HttpStatus.CONFLICT;     // 409
                case EMAIL_DELIVERY_FAILED -> HttpStatus.BAD_GATEWAY;  // 502
                case UPLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;  // 413
                case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED; // 401
                case WORKFLOW_CONFLICT -> HttpStatus.CONFLICT;          // 409
            };
            if (status.is5xxServerError() || status == HttpStatus.UNPROCESSABLE_ENTITY) {
                log.warn("API error {}: {}", e.code(), e.getMessage());
            }
            return ResponseEntity.status(status)
                    .body(new ApiError(e.code().name(), e.getMessage()));
        }

        /**
         * The workflow changed underneath the request (optimistic version
         * conflict, or a save that would have regressed a terminal state or
         * an accepted signature). Deterministic 409: nothing was applied, the
         * client reloads and decides again.
         */
        @ExceptionHandler({WorkflowConflictException.class,
                OptimisticLockingFailureException.class})
        public ResponseEntity<ApiError> handleConflict(RuntimeException e) {
            log.info("Workflow conflict: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError(ApiErrorCode.WORKFLOW_CONFLICT.name(),
                            "This workflow was changed by another request."
                                    + " Reload it and try again."));
        }

        /**
         * Domain exceptions that escaped the service layer. A revision that
         * already exists is a concurrency conflict (409); anything else is an
         * internal failure whose details stay in the server log.
         */
        @ExceptionHandler(SignerException.class)
        public ResponseEntity<ApiError> handleSignerException(
                SignerException e) {
            if (e.getErrorCode() == ErrorCode.REVISION_CONFLICT) {
                log.info("Revision conflict: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ApiError(ApiErrorCode.REVISION_CONFLICT.name(),
                                "This document revision was already submitted."
                                        + " Reload the workflow and try again."));
            }
            log.error("Unhandled domain error ({})", e.getErrorCode(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiError("INTERNAL_ERROR",
                            "The server could not complete the request."));
        }
    }
}
