package com.example.signer.server.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues and validates short-lived JWT access tokens (HS256) for the
 * coordination API.
 *
 * <p>Since security Phase 2 both sides use Spring Security's JWT support
 * ({@link NimbusJwtEncoder} / {@link NimbusJwtDecoder}); {@link #decoder()}
 * is the same decoder the Spring Security filter chain uses, so a token is
 * accepted or rejected by exactly one set of rules: HS256 only, valid
 * signature, issuer, audience, unexpired, {@code exp} and {@code iat}
 * present with a bounded lifetime, then the {@code typ} claim.
 *
 * <p>Claims are deliberately minimal: {@code sub} (workflow user id),
 * {@code iss}, {@code aud}, {@code iat}, {@code exp}, {@code jti} and a
 * {@code typ} discriminator. No password, PIN, certificate or document
 * material ever enters a token.
 *
 * <p>The signing secret comes ONLY from
 * {@code signature.remote-server.jwt-secret} (environment variable
 * {@code SIGNER_SERVER_JWT_SECRET}) and must be at least
 * {@value #MIN_SECRET_BYTES} bytes. It is never derived from anything
 * predictable (paths, user names, host names, configuration).
 *
 * <ul>
 *   <li>Coordination server outside the {@code dev} profile: a missing or
 *       short secret makes startup FAIL (no fallback).</li>
 *   <li>{@code dev} profile, or a context that is not the server (the
 *       desktop scans these beans but never issues tokens): an EPHEMERAL
 *       random key is generated per process. Tokens die with the process —
 *       as do the in-memory refresh sessions — and nobody can predict it.</li>
 * </ul>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    static final String ISSUER = "signer-coordination-server";
    static final String AUDIENCE = "signer-desktop-clients";
    /** Name of the token-type claim. */
    public static final String TYPE_CLAIM = "typ";
    /** {@link #TYPE_CLAIM} of an account access token. */
    public static final String TYPE_ACCESS = "access";
    /**
     * Guest signing sessions (invited signers without a SignFlow account).
     * A different token TYPE on purpose: {@link #validateAccessJwt} rejects
     * it, so a guest token is refused everywhere by default and accepted
     * only where {@link #validateGuestJwt} is called explicitly.
     */
    public static final String TYPE_GUEST = "guest";

    /**
     * Desktop task session opened with a website launch code (Phase B): one
     * account, one workflow, one signer slot, short-lived, never refreshed.
     * Accepted only where a guest token would be ({@code GuestAccessPolicy})
     * and only while its launch code is not revoked.
     */
    public static final String TYPE_TASK = "task";

    private final NimbusJwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration accessTtl;

    /** Reason a token was rejected (logged server-side, never sent to clients). */
    public enum RejectionReason {
        MALFORMED, EXPIRED, WRONG_TYPE, BAD_ISSUER, BAD_AUDIENCE, MISSING_CLAIM
    }

    /** Thrown on any validation failure; carries the {@link RejectionReason}. */
    public static class JwtValidationException extends RuntimeException {
        private final RejectionReason reason;

        public JwtValidationException(RejectionReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public RejectionReason reason() {
            return reason;
        }
    }

    /** The authenticated identity extracted from a validated token. */
    public record JwtAuthentication(String subject, String tokenId,
                                    Instant expiresAt) {
    }

    /**
     * A validated guest signing session. It authorizes access to ONE signer
     * slot of ONE workflow, through ONE invitation — it is not an account
     * login and says nothing about who will cryptographically sign (that is
     * established later by the certificate on the signer's smart card).
     *
     * @param principal    the signer slot's identity key (the workflow's
     *                     internal signer id), used for slot-level checks
     */
    public record GuestAuthentication(String principal, String workflowId,
                                      int signerOrder, long invitationId,
                                      String tokenId, Instant expiresAt) {
    }

    /** Minimum secret length: HS256 needs at least 256 bits of key material. */
    public static final int MIN_SECRET_BYTES = 32;

    @Autowired
    public JwtService(
            @Value("${signature.remote-server.jwt-secret:}") String configuredSecret,
            @Value("${signature.remote-server.access-token-minutes:15}") long accessMinutes,
            @Value("${signature.remote-server.guest-session-minutes:60}") long guestMinutes,
            @Value("${signature.remote-server.desktop.task-session-minutes:30}") long taskMinutes,
            Environment environment) {
        this(configuredSecret, accessMinutes, Math.max(guestMinutes, taskMinutes),
                environment.acceptsProfiles(Profiles.of("server"))
                        && !environment.acceptsProfiles(
                                Profiles.of("dev")));
    }

    /** As configured by Spring, with the default 60-minute guest sessions. */
    public JwtService(String configuredSecret, long accessMinutes, Environment environment) {
        this(configuredSecret, accessMinutes, 60, 30, environment);
    }

    /** Guest sessions limited to the default 60 minutes. */
    public JwtService(String configuredSecret, long accessMinutes, boolean secretRequired) {
        this(configuredSecret, accessMinutes, 60, secretRequired);
    }

    /**
     * @param secretRequired whether a configured secret is mandatory (the
     *        coordination server outside the {@code dev} profile)
     */
    public JwtService(String configuredSecret, long accessMinutes, long guestMinutes,
                      boolean secretRequired) {
        SecretKey key = resolveKey(configuredSecret, secretRequired);
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // No clock skew: an expired token is expired (same as before Phase 2).
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(AUDIENCE)),
                lifetimeValidator(Duration.ofMinutes(Math.max(accessMinutes, guestMinutes)))));
        this.decoder = nimbus;
        log.info("JWT access tokens: HS256, ttl={}min, secret={}", accessMinutes,
                configuredSecret == null || configuredSecret.isBlank()
                        ? "ephemeral random (development / non-server context)"
                        : "configured");
    }

    private static SecretKey resolveKey(String configured, boolean secretRequired) {
        if (configured != null && !configured.isBlank()) {
            byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
            if (secret.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("signature.remote-server.jwt-secret"
                        + " (SIGNER_SERVER_JWT_SECRET) is too short: at least "
                        + MIN_SECRET_BYTES + " bytes of random data are required"
                        + " (e.g. 48 random bytes, base64-encoded)");
            }
            return new SecretKeySpec(secret, "HmacSHA256");
        }
        if (secretRequired) {
            throw new IllegalStateException("SIGNER_SERVER_JWT_SECRET is not set."
                    + " The coordination server refuses to start without an explicit,"
                    + " high-entropy JWT signing secret (at least " + MIN_SECRET_BYTES
                    + " bytes). For local development start with the 'dev' profile.");
        }
        byte[] random = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        return new SecretKeySpec(random, "HmacSHA256");
    }

    /**
     * Requires {@code exp} and {@code iat} (the timestamp validator only
     * checks {@code exp} when present), refuses an {@code iat} in the future
     * beyond {@link #ISSUED_AT_SKEW}, and a lifetime ({@code exp - iat})
     * longer than {@code max}. Every token this server issues passes; a
     * token minted elsewhere with the key but no expiry does not.
     */
    static OAuth2TokenValidator<Jwt> lifetimeValidator(Duration max) {
        return jwt -> {
            Instant issued = jwt.getIssuedAt();
            Instant expires = jwt.getExpiresAt();
            if (issued == null || expires == null) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "exp and iat are required", null));
            }
            if (issued.isAfter(Instant.now().plus(ISSUED_AT_SKEW))) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "iat is in the future", null));
            }
            if (Duration.between(issued, expires).compareTo(max) > 0) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "token lifetime too long", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /** Tolerated clock difference for {@code iat} between server instances. */
    static final Duration ISSUED_AT_SKEW = Duration.ofSeconds(30);

    /**
     * The decoder enforcing signature, algorithm, issuer, audience and
     * expiry. Shared with the Spring Security filter chain. The {@code typ}
     * claim is checked afterwards ({@link #toAccess}, {@link #toGuest}).
     */
    public JwtDecoder decoder() {
        return decoder;
    }

    /** Issues an access token for {@code userId}. */
    public String issueAccessJwt(String userId) {
        Instant now = Instant.now();
        return sign(base(userId, now, now.plus(accessTtl))
                .claim(TYPE_CLAIM, TYPE_ACCESS)
                .build());
    }

    /**
     * Issues a guest signing-session token scoped to one workflow, one
     * signer slot and one invitation. It expires at {@code notAfter} (the
     * invitation's own expiry) or after {@code maxTtl}, whichever is first.
     */
    public String issueGuestJwt(String principal, String workflowId, int signerOrder,
                                long invitationId, Instant notAfter, Duration maxTtl) {
        Instant now = Instant.now();
        Instant expiry = now.plus(maxTtl);
        if (notAfter != null && notAfter.isBefore(expiry)) {
            expiry = notAfter;
        }
        return sign(base(principal, now, expiry)
                .claim(TYPE_CLAIM, TYPE_GUEST)
                .claim("wf", workflowId)
                .claim("slot", signerOrder)
                .claim("inv", invitationId)
                .build());
    }

    /** Seconds until an access token issued now expires. */
    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    /** Validates signature, algorithm, expiry, issuer, audience and token type. */
    public JwtAuthentication validateAccessJwt(String token) {
        return toAccess(decode(token));
    }

    /**
     * Issues a desktop task token (Phase B) for {@code principal}'s slot
     * {@code signerOrder} of {@code workflowId}, opened with launch code
     * {@code launchId}. No refresh token exists for it.
     */
    public String issueTaskJwt(String principal, String workflowId, int signerOrder,
                               long launchId, Duration ttl) {
        Instant now = Instant.now();
        return sign(base(principal, now, now.plus(ttl))
                .claim(TYPE_CLAIM, TYPE_TASK)
                .claim("wf", workflowId)
                .claim("slot", signerOrder)
                .claim("lc", launchId)
                .build());
    }

    /** A validated desktop task session. */
    public record TaskAuthentication(String principal, String workflowId, int signerOrder,
                                     long launchId, String tokenId, Instant expiresAt) {
    }

    /** An already-decoded token as a desktop task session, or a rejection. */
    public static TaskAuthentication toTask(Jwt jwt) {
        if (!TYPE_TASK.equals(jwt.getClaimAsString(TYPE_CLAIM))) {
            throw new JwtValidationException(RejectionReason.WRONG_TYPE, "wrong token type");
        }
        String subject = jwt.getSubject();
        String workflowId = jwt.getClaimAsString("wf");
        Object slot = jwt.getClaim("slot");
        Object lc = jwt.getClaim("lc");
        if (subject == null || subject.isBlank() || workflowId == null || workflowId.isBlank()
                || !(slot instanceof Number s) || !(lc instanceof Number l)) {
            throw new JwtValidationException(RejectionReason.MISSING_CLAIM, "missing claim");
        }
        return new TaskAuthentication(subject, workflowId, s.intValue(), l.longValue(),
                jwt.getId(), jwt.getExpiresAt());
    }

    /** Validates a desktop task token (signature, expiry, type, scope claims). */
    public TaskAuthentication validateTaskJwt(String token) {
        return toTask(decode(token));
    }

    /** Validates a guest signing-session token (signature, expiry, type, scope claims). */
    public GuestAuthentication validateGuestJwt(String token) {
        return toGuest(decode(token));
    }

    /** An already-decoded token as an account access token, or a rejection. */
    public static JwtAuthentication toAccess(Jwt jwt) {
        if (!TYPE_ACCESS.equals(jwt.getClaimAsString(TYPE_CLAIM))) {
            throw new JwtValidationException(RejectionReason.WRONG_TYPE, "wrong token type");
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new JwtValidationException(RejectionReason.MISSING_CLAIM, "missing subject");
        }
        return new JwtAuthentication(subject, jwt.getId(), jwt.getExpiresAt());
    }

    /** An already-decoded token as a guest session, or a rejection. */
    public static GuestAuthentication toGuest(Jwt jwt) {
        if (!TYPE_GUEST.equals(jwt.getClaimAsString(TYPE_CLAIM))) {
            throw new JwtValidationException(RejectionReason.WRONG_TYPE, "wrong token type");
        }
        String subject = jwt.getSubject();
        String workflowId = jwt.getClaimAsString("wf");
        Object slot = jwt.getClaim("slot");
        Object inv = jwt.getClaim("inv");
        if (subject == null || subject.isBlank() || workflowId == null || workflowId.isBlank()
                || !(slot instanceof Number s) || !(inv instanceof Number i)) {
            throw new JwtValidationException(RejectionReason.MISSING_CLAIM, "missing claim");
        }
        return new GuestAuthentication(subject, workflowId, s.intValue(), i.longValue(),
                jwt.getId(), jwt.getExpiresAt());
    }

    private Jwt decode(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtValidationException(RejectionReason.MALFORMED, "token rejected");
        }
        try {
            return decoder.decode(token);
        } catch (org.springframework.security.oauth2.jwt.JwtValidationException e) {
            throw new JwtValidationException(reasonOf(e), "token rejected");
        } catch (BadJwtException e) {
            throw new JwtValidationException(RejectionReason.MALFORMED, "token rejected");
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtValidationException(RejectionReason.MALFORMED, "token rejected");
        }
    }

    /** Which check failed, for the server log only. */
    static RejectionReason reasonOf(org.springframework.security.oauth2.jwt.JwtValidationException e) {
        for (OAuth2Error error : e.getErrors()) {
            String text = error.getDescription() == null ? "" : error.getDescription();
            if (text.contains("expired")) {
                return RejectionReason.EXPIRED;
            }
            if (text.contains(JwtClaimNames.ISS)) {
                return RejectionReason.BAD_ISSUER;
            }
            if (text.contains(JwtClaimNames.AUD)) {
                return RejectionReason.BAD_AUDIENCE;
            }
        }
        return RejectionReason.MALFORMED;
    }

    private static JwtClaimsSet.Builder base(String subject, Instant now, Instant expiry) {
        return JwtClaimsSet.builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiry);
    }

    private String sign(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
