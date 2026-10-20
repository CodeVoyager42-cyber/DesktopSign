package com.example.signer.server.launch;

import com.example.signer.server.api.ApiContracts;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.db.AuditLog;
import com.example.signer.server.db.entity.LaunchCodeEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.entity.WorkflowEntity;
import com.example.signer.server.db.repository.LaunchCodeRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.workflow.AuditEventType;
import com.example.signer.server.workflow.RemoteWorkflowService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase B — one-time launch codes: the website (for now: any signed-in
 * account client) asks for a code for ONE signing task; the desktop app
 * exchanges it once for a short-lived task token.
 *
 * <ul>
 *   <li>Issue: re-checks the user's slot and turn ({@link
 *       RemoteWorkflowService#requireLaunchableSlot}), the invitation state
 *       and a verified e-mail; 32 random bytes from {@link SecureRandom};
 *       only SHA-256 stored; 2 minutes; bound to user, workflow, slot,
 *       purpose and the minimum desktop version; replaces unused codes for
 *       the same slot; at most {@value #MAX_ISSUED_PER_MINUTE} per minute.</li>
 *   <li>Exchange: version check first (independent of the code), then ONE
 *       atomic UPDATE consumes the code, then everything is re-checked. Every
 *       failure is the same {@code LAUNCH_CODE_INVALID}; a consumed code stays
 *       consumed even when a later check fails.</li>
 *   <li>Task token: {@code typ=task}, one workflow + slot, no refresh token,
 *       dies with its launch code's revocation.</li>
 * </ul>
 * Codes and tokens are never logged.
 */
@Service
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class LaunchCodeService {

    private static final Logger log = LoggerFactory.getLogger(LaunchCodeService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(2);
    static final int MAX_ISSUED_PER_MINUTE = 5;
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern DEVICE_UNSAFE = Pattern.compile("[^A-Za-z0-9 ._()-]");

    /** Thrown for every exchange failure; its own type so a consumption is never rolled back. */
    public static class LaunchRejected extends ApiException {
        public LaunchRejected() {
            super(ApiErrorCode.LAUNCH_CODE_INVALID,
                    "This signing link can no longer be used. Start signing again from"
                            + " the SignFlow website.");
        }
    }

    private final LaunchCodeRepository codes;
    private final WorkflowRepository workflows;
    private final UserRepository users;
    private final UserAccountService accounts;
    private final RemoteWorkflowService workflowService;
    private final ObjectProvider<InvitationService> invitations;
    private final JwtService jwtService;
    private final AuditLog auditLog;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final DesktopVersion minDesktopVersion;
    private final boolean acceptUnknownVersion;
    private final Duration taskTtl;

    /** What a desktop that cannot tell its version sends (see WorkflowApiClient). */
    static final String UNKNOWN_VERSION = "unknown";

    public LaunchCodeService(LaunchCodeRepository codes, WorkflowRepository workflows,
                             UserRepository users, UserAccountService accounts,
                             RemoteWorkflowService workflowService,
                             ObjectProvider<InvitationService> invitations,
                             JwtService jwtService, AuditLog auditLog,
                             PlatformTransactionManager transactionManager,
                             @Value("${signature.remote-server.desktop.min-version:0.1.0-SNAPSHOT}")
                             String minDesktopVersion,
                             @Value("${signature.remote-server.desktop.task-session-minutes:30}")
                             long taskMinutes,
                             @Value("${signature.remote-server.desktop.accept-unknown-version:false}")
                             boolean acceptUnknownVersion) {
        this.codes = codes;
        this.workflows = workflows;
        this.users = users;
        this.accounts = accounts;
        this.workflowService = workflowService;
        this.invitations = invitations;
        this.jwtService = jwtService;
        this.auditLog = auditLog;
        this.tx = new TransactionTemplate(transactionManager);
        this.minDesktopVersion = DesktopVersion.parse(minDesktopVersion);
        if (this.minDesktopVersion == null) {
            throw new IllegalStateException("signature.remote-server.desktop.min-version must look"
                    + " like 1.2.3");
        }
        if (taskMinutes < 1 || taskMinutes > 60) {
            throw new IllegalStateException(
                    "signature.remote-server.desktop.task-session-minutes must be 1..60");
        }
        this.taskTtl = Duration.ofMinutes(taskMinutes);
        this.acceptUnknownVersion = acceptUnknownVersion;
    }

    public String minDesktopVersion() {
        return minDesktopVersion.toString();
    }

    /* ------------------------------- issue ------------------------------ */

    /**
     * A new launch code for {@code username}'s slot in {@code workflowPublicId}.
     * Same 404 / 403 / 409 answers as the workflow API when the user may not
     * open it now.
     */
    @Transactional
    public ApiContracts.LaunchCodeIssued issue(String username, String workflowPublicId) {
        // Row lock: concurrent requests of one user are counted one at a
        // time, so the per-minute limit cannot be overrun by racing them.
        UserEntity user = users.lockByUsername(username)
                .orElseThrow(() -> new ApiException(ApiErrorCode.AUTHENTICATION_REQUIRED,
                        "Authentication required"));
        if (user.getEmailVerifiedAt() == null) {
            throw new ApiException(ApiErrorCode.EMAIL_NOT_VERIFIED,
                    "Verify your e-mail address before signing documents");
        }
        int slot = workflowService.requireLaunchableSlot(workflowPublicId, username);
        InvitationService invitationService = invitations.getIfAvailable();
        if (invitationService != null) {
            invitationService.requireSigningAllowed(workflowPublicId, username);
        }
        Instant now = Instant.now();
        if (codes.countByUsernameAndCreatedAtAfter(username, now.minusSeconds(60))
                >= MAX_ISSUED_PER_MINUTE) {
            throw new ApiException(ApiErrorCode.TOO_MANY_REQUESTS,
                    "Too many signing links requested. Wait a minute, then try again.");
        }
        WorkflowEntity wf = workflows.findByWorkflowId(workflowPublicId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND,
                        "Workflow not found"));
        codes.revokeUnused(username, wf.getId(), slot, now);
        String code = newCode();
        codes.save(new LaunchCodeEntity(hash(code), username, wf.getId(), slot,
                LaunchCodeEntity.PURPOSE_SIGN, minDesktopVersion.toString(), now,
                now.plus(CODE_TTL)));
        log.info("Launch code issued for workflow {} signer {} by {}",
                workflowPublicId, slot, username);
        return new ApiContracts.LaunchCodeIssued("signflow://launch?v=1&c=" + code,
                CODE_TTL.toSeconds());
    }

    /* ------------------------------ exchange ---------------------------- */

    /**
     * Exchanges a launch code for a task session. The version check runs
     * before the code is even looked at, so it reveals nothing about codes.
     *
     * <p>Not one transaction on purpose: the consuming UPDATE commits on its
     * own first, so the code is spent even when a later check refuses (a
     * refusal inside another service's transaction would otherwise roll the
     * consumption back).
     */
    public ApiContracts.LaunchSessionView exchange(ApiContracts.LaunchExchangeRequest request) {
        // Compatibility gate, not authentication: the client states its version.
        String reported = request == null ? null : request.appVersion();
        boolean unknown = UNKNOWN_VERSION.equals(reported);
        DesktopVersion appVersion;
        if (unknown && acceptUnknownVersion) {
            appVersion = minDesktopVersion;      // development builds, dev profile only
        } else if (unknown) {
            throw new ApiException(ApiErrorCode.UPGRADE_REQUIRED,
                    "This copy of SignFlow does not report its version (a development"
                            + " build). Install SignFlow " + minDesktopVersion
                            + " or newer, then try again.");
        } else {
            appVersion = DesktopVersion.parse(reported);
        }
        if (appVersion == null || appVersion.compareTo(minDesktopVersion) < 0) {
            throw new ApiException(ApiErrorCode.UPGRADE_REQUIRED,
                    "This version of SignFlow is too old. Install version "
                            + minDesktopVersion + " or newer, then try again.");
        }
        String recordedVersion = unknown ? UNKNOWN_VERSION : appVersion.toString();
        String code = request.code();
        if (code == null || !CODE.matcher(code).matches()) {
            throw new LaunchRejected();
        }
        String codeHash = hash(code);
        Instant now = Instant.now();
        String device = cleanDevice(request.device());
        Integer consumed = tx.execute(status ->
                codes.consume(codeHash, now, recordedVersion, device));
        if (consumed == null || consumed != 1) {
            log.info("Launch code refused (unknown, used, revoked or expired)");
            throw new LaunchRejected();
        }
        LaunchCodeEntity row = codes.findByCodeHash(codeHash).orElseThrow(LaunchRejected::new);
        WorkflowEntity wf = workflows.findById(row.getWorkflowId()).orElseThrow(LaunchRejected::new);
        try {
            DesktopVersion required = DesktopVersion.parse(row.getMinAppVersion());
            if (!LaunchCodeEntity.PURPOSE_SIGN.equals(row.getPurpose())
                    || required == null || appVersion.compareTo(required) < 0) {
                throw new LaunchRejected();
            }
            UserEntity user = users.findByUsername(row.getUsername())
                    .orElseThrow(LaunchRejected::new);
            if (!user.isActive() || user.getEmailVerifiedAt() == null
                    || !accounts.isRegistered(row.getUsername())) {
                throw new LaunchRejected();
            }
            int slot = workflowService.requireLaunchableSlot(wf.getWorkflowId(), row.getUsername());
            if (slot != row.getSignerOrder()) {
                throw new LaunchRejected();
            }
            InvitationService invitationService = invitations.getIfAvailable();
            if (invitationService != null) {
                invitationService.requireSigningAllowed(wf.getWorkflowId(), row.getUsername());
            }
        } catch (LaunchRejected e) {
            log.info("Launch code for workflow {} refused at exchange", wf.getWorkflowId());
            throw e;
        } catch (ApiException e) {
            log.info("Launch code for workflow {} refused at exchange ({})",
                    wf.getWorkflowId(), e.code());
            throw new LaunchRejected();
        }
        String token = jwtService.issueTaskJwt(row.getUsername(), wf.getWorkflowId(),
                row.getSignerOrder(), row.getId(), taskTtl);
        tx.executeWithoutResult(status -> auditLog.append(wf.getId(),
                AuditEventType.DESKTOP_SESSION_OPENED, row.getUsername(),
                "Desktop signing session opened for signer " + row.getSignerOrder()
                        + " from the website (SignFlow " + recordedVersion
                        + (device == null ? "" : ", " + device) + ")"));
        log.info("Desktop task session opened for workflow {} signer {} (app {})",
                wf.getWorkflowId(), row.getSignerOrder(), recordedVersion);
        return new ApiContracts.LaunchSessionView(token, "Bearer", taskTtl.toSeconds(),
                wf.getWorkflowId(), row.getSignerOrder(), row.getUsername(),
                wf.getDocumentName(), minDesktopVersion.toString());
    }

    /* ------------------- task sessions: request-time check --------------- */

    /**
     * Evaluated on every request of a task session. {@code INVALID} unless
     * its launch code is consumed and not revoked, still describes the same
     * account, workflow and slot, and the account is active and verified.
     * Then the slot itself decides: {@code ACTIVE} while it can still be
     * signed, {@code FINISHED} once signed or the workflow has ended.
     */
    @Transactional(readOnly = true)
    public RemoteWorkflowService.TaskSlotState taskSessionState(
            JwtService.TaskAuthentication task) {
        boolean codeMatches = codes.findById(task.launchId())
                .filter(c -> c.getConsumedAt() != null && c.getRevokedAt() == null)
                .filter(c -> c.getUsername().equals(task.principal()))
                .filter(c -> c.getSignerOrder() == task.signerOrder())
                .flatMap(c -> workflows.findById(c.getWorkflowId()))
                .map(w -> w.getWorkflowId().equals(task.workflowId()))
                .orElse(false);
        boolean accountOk = users.findByUsername(task.principal())
                .map(u -> u.isActive() && u.getEmailVerifiedAt() != null)
                .orElse(false);
        if (!codeMatches || !accountOk) {
            return RemoteWorkflowService.TaskSlotState.INVALID;
        }
        return workflowService.taskSlotState(task.workflowId(), task.principal(),
                task.signerOrder());
    }

    /* --------------------- the user's own launches ---------------------- */

    /** The user's 20 most recent launches, for the "opened on device X" notice. */
    @Transactional(readOnly = true)
    public List<ApiContracts.LaunchView> recent(String username) {
        Instant now = Instant.now();
        return codes.findTop20ByUsernameOrderByCreatedAtDesc(username).stream()
                .map(c -> new ApiContracts.LaunchView(c.getId(),
                        workflows.findById(c.getWorkflowId()).map(WorkflowEntity::getWorkflowId)
                                .orElse(null),
                        c.getSignerOrder(), state(c, now), c.getCreatedAt().toString(),
                        c.getExpiresAt().toString(),
                        c.getConsumedAt() == null ? null : c.getConsumedAt().toString(),
                        c.getConsumedAppVersion(), c.getConsumedDevice()))
                .toList();
    }

    /**
     * "This wasn't me": revokes one of the user's own launches; a desktop
     * session opened with it stops working at its next request. Someone
     * else's id gets the same answer as an unknown id.
     */
    @Transactional
    public void revoke(String username, long launchId) {
        LaunchCodeEntity row = codes.findById(launchId)
                .filter(c -> c.getUsername().equals(username))
                .orElseThrow(() -> new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND,
                        "Launch not found"));
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(Instant.now());
            codes.save(row);
            if (row.getConsumedAt() != null) {
                auditLog.append(row.getWorkflowId(), AuditEventType.DESKTOP_SESSION_REVOKED,
                        username, "Desktop signing session for signer " + row.getSignerOrder()
                                + " revoked by its owner");
            }
        }
    }

    /** Hourly: unused codes expired more than a day ago are deleted. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 900_000)
    @Transactional
    public void deleteStaleCodes() {
        int removed = codes.deleteUnusedExpiredBefore(Instant.now().minus(Duration.ofDays(1)));
        if (removed > 0) {
            log.info("Deleted {} unused expired launch code(s)", removed);
        }
    }

    /* ------------------------------ helpers ----------------------------- */

    private static String state(LaunchCodeEntity c, Instant now) {
        if (c.getRevokedAt() != null) {
            return "REVOKED";
        }
        if (c.getConsumedAt() != null) {
            return "OPENED";
        }
        return c.getExpiresAt().isAfter(now) ? "WAITING" : "EXPIRED";
    }

    private String newCode() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A short, printable device label; never trusted, only shown back to the user. */
    static String cleanDevice(String device) {
        if (device == null || device.isBlank()) {
            return null;
        }
        String clean = DEVICE_UNSAFE.matcher(device.trim()).replaceAll("");
        return clean.isEmpty() ? null : clean.substring(0, Math.min(clean.length(), 64));
    }
}
