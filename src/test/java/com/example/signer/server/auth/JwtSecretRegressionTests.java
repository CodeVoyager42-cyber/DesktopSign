package com.example.signer.server.auth;

import io.jsonwebtoken.Jwts;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.2 regression: the JWT signing key is never derived from predictable
 * data (it used to be SHA-256("signer-jwt-dev-key|" + storage path)), and a
 * production coordination server refuses to start without a secret.
 */
class JwtSecretRegressionTests {

    private static final String STRONG_SECRET =
            "q3Vt0x1mZ9kP2wR7yB4nC6dF8gH0jK2lM5oQ7sT9uV1xY3zA";

    @Test
    void productionServerRefusesToStartWithoutASecret() {
        assertThatThrownBy(() -> new JwtService("", 15, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGNER_SERVER_JWT_SECRET");
        assertThatThrownBy(() -> new JwtService(null, 15, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void secretRequirementFollowsTheProfiles() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("server");
        assertThatThrownBy(() -> new JwtService("", 15, production))
                .isInstanceOf(IllegalStateException.class);

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("server", "dev");
        assertThat(new JwtService("", 15, dev).issueAccessJwt("alice")).isNotBlank();
    }

    @Test
    void aShortSecretIsRefused() {
        assertThatThrownBy(() -> new JwtService("too-short", 15, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void theStoragePathNoLongerDeterminesTheKey() {
        // Two development servers with the SAME storage path: before the fix
        // they shared a key, so a token from one validated on the other.
        JwtService first = new JwtService("", 15, false);
        JwtService second = new JwtService("", 15, false);
        String token = first.issueAccessJwt("alice");
        assertThat(first.validateAccessJwt(token).subject()).isEqualTo("alice");
        assertThatThrownBy(() -> second.validateAccessJwt(token))
                .isInstanceOf(JwtService.JwtValidationException.class);
    }

    @Test
    void aTokenForgedWithTheOldPredictableKeyIsRejected() throws Exception {
        String storage = System.getProperty("java.io.tmpdir") + "/signer-server";
        byte[] predictable = MessageDigest.getInstance("SHA-256").digest(
                ("signer-jwt-dev-key|" + storage).getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String forged = Jwts.builder()
                .subject("alice")
                .issuer(JwtService.ISSUER)
                .audience().add(JwtService.AUDIENCE).and()
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(new SecretKeySpec(predictable, "HmacSHA256"), Jwts.SIG.HS256)
                .compact();

        JwtService configured = new JwtService(STRONG_SECRET, 15, true);
        JwtService developmentRandom = new JwtService("", 15, false);
        assertThatThrownBy(() -> configured.validateAccessJwt(forged))
                .isInstanceOf(JwtService.JwtValidationException.class);
        assertThatThrownBy(() -> developmentRandom.validateAccessJwt(forged))
                .isInstanceOf(JwtService.JwtValidationException.class);
        // The configured key itself still works.
        assertThat(configured.validateAccessJwt(configured.issueAccessJwt("bob")).subject())
                .isEqualTo("bob");
    }
}
