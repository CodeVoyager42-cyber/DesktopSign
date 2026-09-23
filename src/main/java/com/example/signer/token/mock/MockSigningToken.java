package com.example.signer.token.mock;

import com.example.signer.exception.ErrorCode;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.SigningToken;
import com.example.signer.token.TokenException;
import com.example.signer.token.TokenInfo;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory token simulating the behaviour of a PKCS#11 hardware token.
 *
 * <p>Used only for development and tests. All cryptographic operations are
 * performed in-process; the "private key" is a plain JVM key pair and is
 * explicitly NOT a hardware-backed key. This class exists to validate the
 * application architecture only.
 *
 * <p>Dev-only default PIN: {@code 1234}. It is not a real credential and must
 * be replaced by a real token's PIN policy in any production deployment.
 */
@Component
public class MockSigningToken implements SigningToken {

    private static final Logger log = LoggerFactory.getLogger(MockSigningToken.class);

    /** Development-only default PIN (SoftHSM-style), clearly not a real secret. */
    private static final char[] DEV_PIN = "1234".toCharArray();

    public static final String DEFAULT_ALIAS = "mock-signing-key";

    /** Second development alias backed by an EC P-256 key (ECDSA). */
    public static final String EC_ALIAS = "mock-ec-signing-key";

    private static final int MAX_PIN_ATTEMPTS = 3;
    private static final int RSA_KEY_SIZE = 2048;
    private static final int EC_KEY_SIZE = 256;

    private static final String RSA_SUBJECT = "CN=Mock Signer, O=Signer Dev, C=FR";
    private static final String EC_SUBJECT = "CN=Mock EC Signer, O=Signer Dev, C=FR";

    private final AtomicBoolean present = new AtomicBoolean(true);

    private volatile boolean authenticated;
    private volatile boolean locked;
    private int failedPinAttempts;

    private volatile TokenInfo tokenInfo;
    private volatile List<SigningCertificate> certificates;
    private final Object initMonitor = new Object();
    private KeyPair rsaKeyPair;
    private KeyPair ecKeyPair;

    /**
     * Returns a fresh copy of the development-only default PIN.
     * <p>Callers receive their own array, so wiping it after use
     * (as {@link com.example.signer.token.TokenService} does) is safe.
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
            privateKey = rsaKeyPair.getPrivate();
        } else if (EC_ALIAS.equals(keyAlias)) {
            if (algorithm != SigningAlgorithm.ECDSA_SHA256) {
                throw new TokenException(ErrorCode.UNSUPPORTED_ALGORITHM,
                        "Algorithm not supported by the EC mock key: " + algorithm);
            }
            privateKey = ecKeyPair.getPrivate();
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
                rsaKeyPair = generateRsaKeyPair();
                ecKeyPair = generateEcKeyPair();
                X509Certificate rsaCertificate =
                        generateSelfSignedCertificate(rsaKeyPair, RSA_SUBJECT);
                X509Certificate ecCertificate =
                        generateSelfSignedCertificate(ecKeyPair, EC_SUBJECT);
                tokenInfo = new TokenInfo(
                        "Mock PKCS#11 Token",
                        "Signer Dev",
                        "Software Mock Token",
                        "MOCK-" + Integer.toUnsignedString(
                                new SecureRandom().nextInt(), 16).toUpperCase());
                certificates = List.of(
                        new SigningCertificate(DEFAULT_ALIAS, rsaCertificate),
                        new SigningCertificate(EC_ALIAS, ecCertificate));
                log.info("Mock token initialized with RSA-{} and EC-{} signing certificates",
                        RSA_KEY_SIZE, EC_KEY_SIZE);
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

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_KEY_SIZE);
        return generator.generateKeyPair();
    }

    private KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private X509Certificate generateSelfSignedCertificate(KeyPair keyPair,
                                                          String subjectDn) throws Exception {
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name(subjectDn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(160, new SecureRandom()),
                new Date(now - 86_400_000L),
                new Date(now + (2L * 365 * 86_400_000L)),
                subject,
                keyPair.getPublic());
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(false));
        String signerAlgorithm = "EC".equals(keyPair.getPublic().getAlgorithm())
                ? "SHA256withECDSA"
                : "SHA256withRSA";
        var holder = builder.build(
                new JcaContentSignerBuilder(signerAlgorithm).build(keyPair.getPrivate()));
        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}