package com.example.signer.server.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Builds deliberately wrong tokens for tests (forged key, wrong claims, no signature). */
public final class TestJwts {

    private TestJwts() {
    }

    /** Claims of a normal access token for {@code subject}, valid for 10 minutes. */
    public static JWTClaimsSet.Builder access(String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(JwtService.ISSUER)
                .audience(JwtService.AUDIENCE)
                .jwtID(UUID.randomUUID().toString())
                .claim("typ", "access")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
    }

    /** Signs {@code claims} with {@code secret} using {@code alg} (an HMAC algorithm). */
    public static String sign(byte[] secret, JWSAlgorithm alg, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String secret, JWSAlgorithm alg, JWTClaimsSet claims) {
        return sign(secret.getBytes(StandardCharsets.UTF_8), alg, claims);
    }

    /** An unsigned token ({@code "alg":"none"}). */
    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }
}
