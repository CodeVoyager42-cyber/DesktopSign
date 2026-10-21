package com.example.signer.server.web;

import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.auth.OneTimeTokens;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.auth.RefreshTokenStore;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.EmailVerificationTokenEntity;
import com.example.signer.server.db.entity.PasswordResetTokenEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.EmailVerificationTokenRepository;
import com.example.signer.server.db.repository.PasswordResetTokenRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.invites.EmailService;
import com.example.signer.server.invites.InvitationLinkResolver;
import com.example.signer.server.security.IdentityRateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Website account self-service (security Phase D): sign-up, e-mail
 * verification, password reset and password change.
 *
 * <p><b>No enumeration, anywhere.</b> {@link #register}, {@link #resendVerification}
 * and {@link #requestPasswordReset} return nothing and never throw for an
 * address that does or does not exist, is or is not already verified, or is
 * over its limit. The caller answers 202 regardless. What differs is only
 * what arrives in the mailbox of whoever actually controls the address.
 *
 * <p><b>Tokens.</b> 256 bits of randomness, e-mailed once, stored only as a
 * SHA-256 hash ({@link OneTimeTokens}), single use, and short-lived —
 * {@value #VERIFICATION_HOURS} h for verification, {@value #RESET_MINUTES} min
 * for a reset. Issuing a new one retires the account's older ones, and
 * consuming one is an update guarded by {@code consumed_at IS NULL} under a
 * row lock, so two clicks cannot both win. Rows that expired over a day ago
 * are deleted hourly by {@link #deleteExpiredTokens()}.
 *
 * <p><b>Revocation.</b> A reset or a change revokes the account's website
 * sessions AND its desktop refresh tokens (Phase C risk C8). A reset ends
 * every session; a change made by the signed-in user keeps the browser they
 * are using and ends the rest.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebAccountService {

    /** How long a verification link lives. */
    public static final int VERIFICATION_HOURS = 24;
    /** How long a password-reset link lives. */
    public static final int RESET_MINUTES = 30;

    /** At most this many e-mails per address per window. */
    private static final int MAILS_PER_IDENTITY = 3;
    private static final Duration MAIL_WINDOW = Duration.ofMinutes(15);
    /** At most this many token submissions per account per window. */
    private static final int ATTEMPTS_PER_IDENTITY = 10;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    /**
     * How long an expired token row is kept before the sweep deletes it. The
     * row is unusable from the moment it expires — every path checks
     * {@code expires_at} — so this grace period is for looking into a support
     * question ("was a link ever issued?"), not for correctness.
     */
    private static final Duration TOKEN_RETENTION = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(WebAccountService.class);

    private final UserRepository users;
    private final EmailVerificationTokenRepository verifications;
    private final PasswordResetTokenRepository resets;
    private final EmailService email;
    private final InvitationLinkResolver links;
    private final RefreshTokenStore refreshTokens;
    private final WebSessionRegistry webSessions;
    private final Clock clock;

    private final IdentityRateLimiter mailLimiter;
    private final IdentityRateLimiter attemptLimiter;

    @org.springframework.beans.factory.annotation.Autowired
    public WebAccountService(UserRepository users,
                             EmailVerificationTokenRepository verifications,
                             PasswordResetTokenRepository resets,
                             EmailService email,
                             InvitationLinkResolver links,
                             RefreshTokenStore refreshTokens,
                             WebSessionRegistry webSessions,
                             @Value("${signature.remote-server.web.mail-limit:3}") int mailLimit) {
        this(users, verifications, resets, email, links, refreshTokens, webSessions,
                Clock.systemUTC(), mailLimit);
    }

    WebAccountService(UserRepository users,
                      EmailVerificationTokenRepository verifications,
                      PasswordResetTokenRepository resets,
                      EmailService email,
                      InvitationLinkResolver links,
                      RefreshTokenStore refreshTokens,
                      WebSessionRegistry webSessions,
                      Clock clock,
                      int mailLimit) {
        this.users = users;
        this.verifications = verifications;
        this.resets = resets;
        this.email = email;
        this.links = links;
        this.refreshTokens = refreshTokens;
        this.webSessions = webSessions;
        this.clock = clock;
        this.mailLimiter = new IdentityRateLimiter(
                mailLimit < 1 ? MAILS_PER_IDENTITY : mailLimit, MAIL_WINDOW, clock);
        this.attemptLimiter = new IdentityRateLimiter(
                ATTEMPTS_PER_IDENTITY, ATTEMPT_WINDOW, clock);
    }

    /* ------------------------------ sign-up ---------------------------- */

    /**
     * Creates an account and e-mails a verification link — or does nothing at
     * all, if the address is taken or over its limit. The answer is the same
     * either way, so this cannot be used to discover who has an account.
     */
    @Transactional
    public void register(String rawEmail, String password, String displayName) {
        String address = normalize(rawEmail);
        if (address == null || !looksLikeEmail(address)) {
            return; // nothing to tell the caller: the answer is always 202
        }
        // The password rules are the only thing a caller may learn, because
        // they are about the request, not about who exists.
        requireValidPassword(password);
        if (!this.mailLimiter.tryAcquire(address)) {
            log.info("Sign-up e-mail suppressed: address over its limit");
            return;
        }
        Optional<UserEntity> existing = this.users.findByEmail(address);
        if (existing.isPresent()) {
            // Taken. Do NOT reveal that, and do NOT touch the account: a
            // stranger must not be able to change anything about it. The
            // address owner gets a verification link only if they are still
            // unverified, which doubles as "resend" for them.
            UserEntity account = existing.get();
            if (account.getEmailVerifiedAt() == null && account.isActive()) {
                issueVerification(account, address);
            }
            return;
        }
        String username = freeUsernameFor(address);
        UserEntity account = new UserEntity(username, address,
                PasswordHashing.hash(password),
                displayName == null || displayName.isBlank() ? username : displayName.trim(),
                AccountStatus.ACTIVE);
        this.users.save(account);
        log.info("Website sign-up: account '{}' created, awaiting e-mail verification",
                username);
        issueVerification(account, address);
    }

    /**
     * Confirms an address from a verification link.
     *
     * @return true when the address is now verified; false for a token that is
     *         malformed, unknown, expired or already used — all answered the
     *         same way
     */
    @Transactional
    public boolean verifyEmail(String rawToken) {
        if (!OneTimeTokens.looksLikeToken(rawToken)) {
            return false;
        }
        Optional<EmailVerificationTokenEntity> found =
                this.verifications.lockByTokenHash(OneTimeTokens.hash(rawToken));
        if (found.isEmpty()) {
            return false;
        }
        EmailVerificationTokenEntity token = found.get();
        Instant now = this.clock.instant();
        if (!token.isUsable(now) || !this.attemptLimiter.tryAcquire(token.getUsername())) {
            return false;
        }
        Optional<UserEntity> account = this.users.findByUsername(token.getUsername());
        if (account.isEmpty()) {
            return false;
        }
        UserEntity user = account.get();
        // The token proves the address it was SENT to. If the account's
        // address has changed since, this token says nothing about the new
        // one, so it verifies nothing.
        if (!token.getEmail().equalsIgnoreCase(normalize(user.getEmail()))) {
            token.setConsumedAt(now);
            log.info("Verification link refused: the account's address changed since it was sent");
            return false;
        }
        token.setConsumedAt(now);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
            log.info("E-mail verified for account '{}'", user.getUsername());
        }
        return true;
    }

    /** Sends a fresh verification link, or does nothing. Always answered 202. */
    @Transactional
    public void resendVerification(String rawEmail) {
        String address = normalize(rawEmail);
        if (address == null || !looksLikeEmail(address)
                || !this.mailLimiter.tryAcquire(address)) {
            return;
        }
        this.users.findByEmail(address)
                .filter(UserEntity::isActive)
                .filter(user -> user.getEmailVerifiedAt() == null)
                .ifPresent(user -> issueVerification(user, address));
    }

    /* --------------------------- password reset ------------------------ */

    /** Sends a reset link, or does nothing. Always answered 202. */
    @Transactional
    public void requestPasswordReset(String rawEmail) {
        String address = normalize(rawEmail);
        if (address == null || !looksLikeEmail(address)
                || !this.mailLimiter.tryAcquire(address)) {
            return;
        }
        Optional<UserEntity> found = this.users.findByEmail(address)
                .filter(UserEntity::isActive);
        if (found.isEmpty()) {
            // An unknown or disabled account sends nothing and says nothing.
            log.info("Password-reset request for an address that cannot receive one");
            return;
        }
        UserEntity user = found.get();
        Instant now = this.clock.instant();
        Instant expiry = now.plus(Duration.ofMinutes(RESET_MINUTES));
        // A new link supersedes any older one.
        this.resets.consumeAllForUser(user.getUsername(), now);
        String raw = OneTimeTokens.newToken();
        this.resets.save(new PasswordResetTokenEntity(
                OneTimeTokens.hash(raw), user.getUsername(), now, expiry));
        log.info("Password-reset link sent for account '{}'", user.getUsername());
        this.email.sendPasswordReset(address, link("reset-password", raw), expiry);
    }

    /**
     * Sets a new password from a reset link and signs the account out
     * everywhere.
     *
     * @return true when the password was changed; false for a token that is
     *         malformed, unknown, expired or already used
     */
    @Transactional
    public boolean confirmPasswordReset(String rawToken, String newPassword) {
        if (!OneTimeTokens.looksLikeToken(rawToken)) {
            return false;
        }
        Optional<PasswordResetTokenEntity> found =
                this.resets.lockByTokenHash(OneTimeTokens.hash(rawToken));
        if (found.isEmpty()) {
            return false;
        }
        PasswordResetTokenEntity token = found.get();
        Instant now = this.clock.instant();
        if (!token.isUsable(now) || !this.attemptLimiter.tryAcquire(token.getUsername())) {
            return false;
        }
        // Only validated once the token is known good, so the rules cannot be
        // probed with a guessed token.
        requireValidPassword(newPassword);
        Optional<UserEntity> account = this.users.findByUsername(token.getUsername());
        if (account.isEmpty() || !account.get().isActive()) {
            token.setConsumedAt(now);
            return false;
        }
        UserEntity user = account.get();
        token.setConsumedAt(now);
        applyNewPassword(user, newPassword, now);
        // Whoever asked for this may not be the one who had the old password,
        // so nothing that was signed in keeps its access.
        this.webSessions.revokeAll(user.getUsername());
        this.refreshTokens.revokeAllForUser(user.getUsername());
        log.info("Password reset for account '{}' — every session revoked", user.getUsername());
        notifyPasswordChanged(user, now, true);
        return true;
    }

    /* -------------------------- password change ------------------------ */

    /**
     * Changes the signed-in user's password. Their current password is
     * required, and every OTHER session is ended.
     *
     * @param keepSessionId the caller's own session, which stays signed in
     * @return true when changed; false when the current password is wrong
     */
    @Transactional
    public boolean changePassword(String username, String currentPassword,
                                  String newPassword, String keepSessionId) {
        Optional<UserEntity> account = this.users.findByUsername(username)
                .filter(UserEntity::isActive);
        if (account.isEmpty()) {
            return false;
        }
        UserEntity user = account.get();
        if (currentPassword == null
                || !PasswordHashing.matches(currentPassword, user.getPasswordHash())) {
            log.info("Password change refused for '{}': wrong current password", username);
            return false;
        }
        requireValidPassword(newPassword);
        Instant now = this.clock.instant();
        applyNewPassword(user, newPassword, now);
        this.webSessions.revokeAllExcept(user.getUsername(), keepSessionId);
        this.refreshTokens.revokeAllForUser(user.getUsername());
        log.info("Password changed for account '{}' — other sessions revoked", username);
        notifyPasswordChanged(user, now, false);
        return true;
    }

    /**
     * Tells the account holder their password changed (risk D8) — the usual
     * way someone notices an account being taken over.
     *
     * <p><b>Best-effort, on purpose.</b> The password has already changed and
     * the sessions are already gone; a mail server that is down must not undo
     * any of that, so a failure is logged and swallowed rather than rolling
     * the transaction back.
     *
     * <p><b>Not counted against the per-recipient e-mail cap</b>, also on
     * purpose. Each of these requires a successful password change — a valid
     * one-time token, or the current password — so it cannot be used to flood
     * anyone, and the cap suppressing exactly the message that says "someone
     * changed your password" would defeat the point of having it.
     */
    private void notifyPasswordChanged(UserEntity user, Instant when, boolean afterReset) {
        String address = normalize(user.getEmail());
        if (address == null) {
            return; // no address on file; nothing to notify
        }
        try {
            this.email.sendPasswordChanged(address, when, afterReset);
        } catch (RuntimeException e) {
            // The class of failure, never the address or the exception's text.
            log.warn("Password-change notice could not be sent for account '{}' ({})",
                    user.getUsername(), e.getClass().getSimpleName());
        }
    }

    /* ------------------------------- sweep ----------------------------- */

    /**
     * Hourly: token rows that expired more than {@link #TOKEN_RETENTION} ago
     * are deleted (security Phase D1 risk D5).
     *
     * <p>Nothing depends on this for safety — an expired token is refused on
     * every path, and a consumed one is retired by {@code consumed_at}. It
     * exists because the two tables otherwise only ever grow: every sign-up,
     * resend and reset request adds a row, and open sign-up means anyone can
     * add them.
     *
     * <p>The same shape as {@code DbRefreshTokenStore.deleteExpired} and
     * {@code LaunchCodeService.deleteStaleCodes}, with a later initial delay
     * than either so the three sweeps do not all run at once on start-up.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 1_200_000)
    @Transactional
    public void deleteExpiredTokens() {
        Instant cutoff = this.clock.instant().minus(TOKEN_RETENTION);
        int staleVerifications = this.verifications.deleteExpiredBefore(cutoff);
        int staleResets = this.resets.deleteExpiredBefore(cutoff);
        if (staleVerifications + staleResets > 0) {
            // Counts only: a token hash is still a secret's shadow.
            log.info("Deleted {} expired verification token row(s) and {} expired"
                    + " password-reset token row(s)", staleVerifications, staleResets);
        }
    }

    /* ------------------------------ helpers ---------------------------- */

    /**
     * Enforces the password rules. {@code PasswordHashing.validateNewPassword}
     * RETURNS the problem rather than throwing, so it has to be checked —
     * ignoring the result silently accepts anything.
     */
    private static void requireValidPassword(String password) {
        String problem = PasswordHashing.validateNewPassword(password);
        if (problem != null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, problem);
        }
    }

    private void applyNewPassword(UserEntity user, String newPassword, Instant now) {
        user.changePasswordHash(PasswordHashing.hash(newPassword), now);
        // Any outstanding reset link is now meaningless.
        this.resets.consumeAllForUser(user.getUsername(), now);
    }

    private void issueVerification(UserEntity account, String address) {
        Instant now = this.clock.instant();
        Instant expiry = now.plus(Duration.ofHours(VERIFICATION_HOURS));
        this.verifications.consumeAllForUser(account.getUsername(), now);
        String raw = OneTimeTokens.newToken();
        this.verifications.save(new EmailVerificationTokenEntity(
                OneTimeTokens.hash(raw), account.getUsername(), address, now, expiry));
        log.info("Verification link sent for account '{}'", account.getUsername());
        this.email.sendEmailVerification(address, link("verify-email", raw), expiry);
    }

    /** The website URL carrying a one-time token. */
    private String link(String page, String rawToken) {
        String base = this.links.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + page + "?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    /**
     * A username derived from the address, with a numeric suffix if needed.
     * Usernames are the identity every workflow and audit row references, so
     * they stay short and stable rather than being the address itself.
     */
    private String freeUsernameFor(String address) {
        String base = address.substring(0, address.indexOf('@'))
                .replaceAll("[^A-Za-z0-9._-]", "")
                .toLowerCase(Locale.ROOT);
        if (base.isBlank()) {
            base = "user";
        }
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        if (!this.users.existsByUsernameIgnoreCase(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String candidate = base + suffix;
            if (!this.users.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free username for that address");
    }

    private static String normalize(String email) {
        return email == null || email.isBlank() ? null
                : UserEntity.normalizeEmail(email.trim());
    }

    /** Deliberately loose: delivery is the real check, not a regex. */
    private static boolean looksLikeEmail(String address) {
        int at = address.indexOf('@');
        return at > 0 && at < address.length() - 1
                && address.indexOf('@', at + 1) < 0
                && address.length() <= 255
                && !address.contains(" ");
    }
}
