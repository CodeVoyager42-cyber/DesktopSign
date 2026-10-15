package com.example.signer.server.auth;

import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues and validates short-lived JWT access tokens (HS256) for the
 * coordination API (Phase 3 of the remote-workflow auth refactor).
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
    private static final String TYPE_ACCESS = "access";
    /**
     * Guest signing sessions (invited signers without a SignFlow account).
     * A different token TYPE on purpose: {@link #validateAccessJwt} rejects
     * it, so a guest token is refused everywhere by default and accepted
     * only where {@link #validateGuestJwt} is called explicitly.
     */
    private static final String TYPE_GUEST = "guest";

    private final SecretKey key;
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
        this.key = resolveKey(configuredSecret, secretRequired);
        this.accessTtl = Duration.ofMinutes(accessMinutes);
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

    /** Issues an access token for {@code userId}. */
    public String issueAccessJwt(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .claim("typ", TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
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
        return Jwts.builder()
                .subject(principal)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .claim("typ", TYPE_GUEST)
                .claim("wf", workflowId)
                .claim("slot", signerOrder)
                .claim("inv", invitationId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Validates a guest signing-session token (signature, expiry, type, scope claims). */
    public GuestAuthentication validateGuestJwt(String token) {
        Claims claims = parse(token);
        if (!TYPE_GUEST.equals(claims.get("typ", String.class))) {
            throw new JwtValidationException(RejectionReason.WRONG_TYPE, "wrong token type");
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)) {
            throw new JwtValidationException(RejectionReason.BAD_AUDIENCE, "bad audience");
        }
        String subject = claims.getSubject();
        String workflowId = claims.get("wf", String.class);
        Number slot = claims.get("slot", Number.class);
        Number inv = claims.get("inv", Number.class);
        if (subject == null || subject.isBlank() || workflowId == null || workflowId.isBlank()
                || slot == null || inv == null) {
            throw new JwtValidationException(RejectionReason.MISSING_CLAIM, "missing claim");
        }
        return new GuestAuthentication(subject, workflowId, slot.intValue(), inv.longValue(),
                claims.getId(), claims.getExpiration().toInstant());
    }

    private Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new JwtValidationException(RejectionReason.EXPIRED, "token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtValidationException(RejectionReason.MALFORMED, "token rejected");
        }
    }

    /** Seconds until an access token issued now expires. */
    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    /** Validates signature, expiry, issuer, audience and token type. */
    public JwtAuthentication validateAccessJwt(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new JwtValidationException(RejectionReason.EXPIRED, "token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtValidationException(RejectionReason.MALFORMED,
                    "token rejected");
        }
        if (!TYPE_ACCESS.equals(claims.get("typ", String.class))) {
            throw new JwtValidationException(RejectionReason.WRONG_TYPE,
                    "wrong token type");
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)) {
            throw new JwtValidationException(RejectionReason.BAD_AUDIENCE,
                    "bad audience");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new JwtValidationException(RejectionReason.MISSING_CLAIM,
                    "missing subject");
        }
        return new JwtAuthentication(subject, claims.getId(),
                claims.getExpiration().toInstant());
    }
}
