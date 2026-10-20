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
 * signature, issuer, audience, unexpired, then the {@code typ} claim.
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
            Environment environment) {
        this(configuredSecret, accessMinutes,
                environment.acceptsProfiles(Profiles.of("server"))
                        && !environment.acceptsProfiles(
                                Profiles.of("dev")));
    }

    /**
     * @param secretRequired whether a configured secret is mandatory (the
     *        coordination server outside the {@code dev} profile)
     */
    public JwtService(String configuredSecret, long accessMinutes, boolean secretRequired) {
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
                        aud -> aud != null && aud.contains(AUDIENCE))));
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
