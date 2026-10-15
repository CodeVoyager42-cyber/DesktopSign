package com.example.signer.token.mock;

import com.example.signer.config.ProductionSafetyValidator;
import com.example.signer.token.DevelopmentSigningIdentities;
import com.example.signer.token.TokenService;
import com.example.signer.exception.ErrorCode;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.SigningToken;
import com.example.signer.token.TokenException;
import com.example.signer.token.TokenInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.security.cert.X509Certificate;

import java.security.SecureRandom;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DEVELOPMENT-ONLY software token simulating a PKCS#11 hardware token.
 *
 * <p>All cryptographic operations are performed in-process; the "private
 * key" is a plain JVM key pair and is explicitly NOT a hardware-backed key.
 * The bean exists only in the {@code dev} Spring profile — a production
 * context never contains it, and production startup fails fast when no real
 * PKCS#11 token is configured (see
 * {@code ProductionSafetyValidator}).
 *
 * <p>Keys are random per installation ({@link MockKeyMaterial}); nothing is
 * derived from constants, so two installations never share a signing
 * identity. Certificates are marked
 * ({@link DevelopmentSigningIdentities#MOCK_MARKER_OU})
 * and rejected by a production coordination server.
 *
 * <p>Dev-only PIN: {@code 1234}. It only ever unlocks these throw-away
 * development keys; no production component accepts mock signatures.
 */
@Component
@Profile("dev")
public class MockSigningToken implements SigningToken {

    private static final Logger log = LoggerFactory.getLogger(MockSigningToken.class);

    /** Development-only default PIN (SoftHSM-style), clearly not a real secret. */
    private static final char[] DEV_PIN = "1234".toCharArray();

    public static final String DEFAULT_ALIAS = "mock-signing-key";

    /** Second development alias backed by an EC P-256 key (ECDSA). */
    public static final String EC_ALIAS = "mock-ec-signing-key";

    /**
     * Third development alias (RSA): a distinct signing identity so the
     * portable cross-PC workflow can be exercised with three different
     * certificates (A → B → C) on the shared mock token.
     */
    public static final String THIRD_ALIAS = "mock-third-signing-key";

    private static final int MAX_PIN_ATTEMPTS = 3;
    private static final int RSA_KEY_SIZE = 2048;
    private static final int EC_KEY_SIZE = 256;

    /**
     * Keystore holding this installation's development keys, or {@code null}
     * for in-memory keys (per JVM).
     */
    private final Path keystore;

    private final AtomicBoolean present = new AtomicBoolean(true);

    private volatile boolean authenticated;
    private volatile boolean locked;
    private int failedPinAttempts;

    private volatile TokenInfo tokenInfo;
    private volatile List<SigningCertificate> certificates;
    private final Object initMonitor = new Object();
    private PrivateKey rsaKey;
    private PrivateKey thirdKey;
    private PrivateKey ecKey;

    /** In-memory development keys (unit tests, ad-hoc use). */
    public MockSigningToken() {
        this((Path) null);
    }

    /**
     * Spring constructor: keys persisted per installation in
     * {@code signature.mock-token.keystore} (set by the {@code dev} profile),
     * in memory when the property is empty.
     */
    @Autowired
    public MockSigningToken(@Value("${signature.mock-token.keystore:}") String keystore) {
        this(keystore == null || keystore.isBlank() ? null : Path.of(keystore));
    }

    /** Development keys persisted in {@code keystore} ({@code null} = in memory). */
    public MockSigningToken(Path keystore) {
        this.keystore = keystore;
    }

    /**
     * Returns a fresh copy of the development-only default PIN.
     * <p>Callers receive their own array, so wiping it after use
     * (as {@link TokenService} does) is safe.
     */
    public static char[] defaultPin() {
        return DEV_PIN.clone();
    }

    /**
     * Simulates inserting the token.
     */
    public void setPresent(boolean value) {
        present.set(value);
        if (!value) {
            resetSession();
        }
        log.debug("Mock token present = {}", value);
    }

    public boolean isPresent() {
        return present.get();
    }

    @Override
    public TokenInfo getTokenInfo() {
        checkPresent();
        ensureInitialized();
        return tokenInfo;
    }

    @Override
    public List<SigningCertificate> getSigningCertificates() {
        checkPresent();
        ensureInitialized();
        return certificates;
    }

    @Override
    public void authenticate(char[] pin) {
        checkPresent();
        ensureInitialized();
        if (pin == null) {
            throw new TokenException(ErrorCode.TOKEN_LOGIN_FAILED, "No PIN provided");
        }
        if (locked) {
            throw new TokenException(ErrorCode.TOKEN_PIN_LOCKED,
                    "Token is locked. Re-insert the token to continue.");
        }
        if (Arrays.equals(pin, DEV_PIN)) {
            authenticated = true;
            failedPinAttempts = 0;
            log.info("Mock token authenticated");
            return;
        }
        failedPinAttempts++;
        if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
            locked = true;
            authenticated = false;
            log.warn("Mock token PIN locked after {} failed attempts", failedPinAttempts);
            throw new TokenException(ErrorCode.TOKEN_PIN_LOCKED,
                    "Too many incorrect PIN attempts. Token locked. Re-insert the token to continue.");
        }
        log.warn("Mock token authentication failed (attempt {}/{})",
                failedPinAttempts, MAX_PIN_ATTEMPTS);
        throw new TokenException(ErrorCode.TOKEN_PIN_INCORRECT,
                "Incorrect token PIN");
    }

    @Override
    public byte[] sign(String keyAlias, byte[] data, SigningAlgorithm algorithm) {
        checkPresent();
        ensureInitialized();
        if (!authenticated) {
            throw new TokenException(ErrorCode.TOKEN_LOGIN_FAILED,
                    "Token is not authenticated");
        }
        PrivateKey privateKey;
        if (DEFAULT_ALIAS.equals(keyAlias)) {
            if (algorithm != SigningAlgorithm.RSA_SHA256) {
                throw new TokenException(ErrorCode.UNSUPPORTED_ALGORITHM,
                        "Algorithm not supported by the RSA mock key: " + algorithm);
            }
            privateKey = rsaKey;
        } else if (THIRD_ALIAS.equals(keyAlias)) {
            if (algorithm != SigningAlgorithm.RSA_SHA256) {
                throw new TokenException(ErrorCode.UNSUPPORTED_ALGORITHM,
                        "Algorithm not supported by the third RSA mock key: " + algorithm);
            }
            privateKey = thirdKey;
        } else if (EC_ALIAS.equals(keyAlias)) {
            if (algorithm != SigningAlgorithm.ECDSA_SHA256) {
                throw new TokenException(ErrorCode.UNSUPPORTED_ALGORITHM,
                        "Algorithm not supported by the EC mock key: " + algorithm);
            }
            privateKey = ecKey;
        } else {
            throw new TokenException(ErrorCode.PRIVATE_KEY_NOT_AVAILABLE,
                    "No signing key available for alias: " + keyAlias);
        }
        try {
            Signature signature = Signature.getInstance(algorithm.getJcaName());
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            log.error("Mock token signing operation failed", e);
            throw new TokenException(ErrorCode.TOKEN_SESSION_FAILED,
                    "Signing operation failed", e);
        }
    }

    @Override
    public void logout() {
        authenticated = false;
        log.info("Mock token logged out");
    }

    @Override
    public void close() {
        resetSession();
        log.debug("Mock token session closed");
    }

    private void checkPresent() {
        if (!present.get()) {
            throw new TokenException(ErrorCode.TOKEN_NOT_CONNECTED,
                    "Token is not connected");
        }
    }

    private void ensureInitialized() {
        if (certificates != null) {
            return;
        }
        synchronized (initMonitor) {
            if (certificates != null) {
                return;
            }
            try {
                List<MockKeyMaterial.Identity> identities = MockKeyMaterial.obtain(keystore);
                MockKeyMaterial.Identity rsa = identities.get(0);
                MockKeyMaterial.Identity third = identities.get(1);
                MockKeyMaterial.Identity ec = identities.get(2);
                rsaKey = rsa.privateKey();
                thirdKey = third.privateKey();
                ecKey = ec.privateKey();
                tokenInfo = new TokenInfo(
                        "Mock PKCS#11 Token (DEVELOPMENT)",
                        "Signer Dev",
                        "Software Mock Token",
                        "MOCK-" + Integer.toUnsignedString(
                                new SecureRandom().nextInt(), 16).toUpperCase());
                certificates = List.of(
                        new SigningCertificate(DEFAULT_ALIAS, rsa.certificate()),
                        new SigningCertificate(THIRD_ALIAS, third.certificate()),
                        new SigningCertificate(EC_ALIAS, ec.certificate()));
                log.warn("DEVELOPMENT mock token in use (per-installation random keys):"
                                + " {} [{}], {} [{}], {} [{}]",
                        DEFAULT_ALIAS, shortFp(rsa.certificate()),
                        THIRD_ALIAS, shortFp(third.certificate()),
                        EC_ALIAS, shortFp(ec.certificate()));
            } catch (Exception e) {
                log.error("Failed to initialize mock token", e);
                throw new TokenException(ErrorCode.TOKEN_SESSION_FAILED,
                        "Failed to initialize token", e);
            }
        }
    }

    private void resetSession() {
        authenticated = false;
        locked = false;
        failedPinAttempts = 0;
    }

    /** First 16 hex chars of the certificate fingerprint, for dev logs. */
    private static String shortFp(X509Certificate certificate) {
        return SigningCertificate.fingerprintOf(certificate)
                .replace(":", "").substring(0, 16) + "…";
    }
}