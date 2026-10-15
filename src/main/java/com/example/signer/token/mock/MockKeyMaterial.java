package com.example.signer.token.mock;

import java.nio.file.attribute.PosixFilePermissions;
import com.example.signer.token.DevelopmentSigningIdentities;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Key material of the DEVELOPMENT mock token.
 *
 * <p>Every identity is generated with fresh entropy ({@link SecureRandom}):
 * two installations never share a key. Nothing is derived from a constant.
 *
 * <ul>
 *   <li>With a keystore path (the {@code dev} profile configures
 *       {@code ~/.signflow/dev-mock-token.p12}), the keys are created once
 *       per installation and reloaded afterwards, so certificate
 *       fingerprints bound to a workflow survive an application restart —
 *       like a real USB token keeps its certificates.</li>
 *   <li>Without a path (unit tests), the keys live in memory for the JVM.</li>
 * </ul>
 *
 * <p>Every certificate subject carries
 * {@link DevelopmentSigningIdentities#MOCK_MARKER_OU}, so a production server
 * can recognise and reject mock signatures.
 */
final class MockKeyMaterial {

    private static final Logger log = LoggerFactory.getLogger(MockKeyMaterial.class);

    /**
     * Password of the development keystore file. It only guards a file of
     * throw-away development keys on the developer's own machine; the key
     * material itself is random per installation.
     */
    private static final char[] KEYSTORE_PASSWORD = "signflow-dev-mock-token".toCharArray();

    /** One alias of the mock token: its key pair and self-signed certificate. */
    record Identity(String alias, PrivateKey privateKey, X509Certificate certificate) {
    }

    private static final ConcurrentHashMap<String, List<Identity>> CACHE = new ConcurrentHashMap<>();

    private MockKeyMaterial() {
    }

    /**
     * The identities for {@code keystore} (created on first use), or the
     * per-JVM in-memory identities when {@code keystore} is {@code null}.
     */
    static List<Identity> obtain(Path keystore) {
        String key = keystore == null ? "<memory>" : keystore.toAbsolutePath().normalize().toString();
        return CACHE.computeIfAbsent(key, k -> keystore == null
                ? generate()
                : loadOrCreate(keystore.toAbsolutePath().normalize()));
    }

    private static List<Identity> loadOrCreate(Path keystore) {
        try {
            if (Files.exists(keystore)) {
                List<Identity> loaded = load(keystore);
                log.info("Development mock token keys loaded from {}", keystore);
                return loaded;
            }
            List<Identity> fresh = generate();
            store(keystore, fresh);
            log.warn("Development mock token: generated NEW random keys for this"
                    + " installation in {} (development only — never trusted by a"
                    + " production server)", keystore);
            return fresh;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "The development mock token keystore " + keystore + " could not be"
                            + " read or created", e);
        }
    }

    private static List<Identity> generate() {
        try {
            SecureRandom random = new SecureRandom();
            List<Identity> identities = new ArrayList<>(3);
            identities.add(identity(MockSigningToken.DEFAULT_ALIAS, "Mock Signer",
                    rsa(random), random));
            identities.add(identity(MockSigningToken.THIRD_ALIAS, "Mock Third Signer",
                    rsa(random), random));
            identities.add(identity(MockSigningToken.EC_ALIAS, "Mock EC Signer",
                    ec(random), random));
            return List.copyOf(identities);
        } catch (Exception e) {
            throw new IllegalStateException("Mock token key generation failed", e);
        }
    }

    private static KeyPair rsa(SecureRandom random) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, random);
        return generator.generateKeyPair();
    }

    private static KeyPair ec(SecureRandom random) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        return generator.generateKeyPair();
    }

    private static Identity identity(String alias, String commonName, KeyPair keyPair,
                                     SecureRandom random) throws Exception {
        X500Name subject = new X500Name("CN=" + commonName
                + ", OU=" + DevelopmentSigningIdentities.MOCK_MARKER_OU
                + ", O=Signer Dev, C=FR");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(127, random).add(BigInteger.ONE),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(5 * 365))),
                subject,
                keyPair.getPublic());
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(false));
        String algorithm = "EC".equals(keyPair.getPublic().getAlgorithm())
                ? "SHA256withECDSA" : "SHA256withRSA";
        var holder = builder.build(new JcaContentSignerBuilder(algorithm)
                .setSecureRandom(random)
                .build(keyPair.getPrivate()));
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
        return new Identity(alias, keyPair.getPrivate(), certificate);
    }

    private static List<Identity> load(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, KEYSTORE_PASSWORD);
        }
        List<Identity> identities = new ArrayList<>(3);
        for (String alias : List.of(MockSigningToken.DEFAULT_ALIAS,
                MockSigningToken.THIRD_ALIAS, MockSigningToken.EC_ALIAS)) {
            PrivateKey key = (PrivateKey) ks.getKey(alias, KEYSTORE_PASSWORD);
            Certificate cert = ks.getCertificate(alias);
            if (key == null || !(cert instanceof X509Certificate x509)) {
                throw new IllegalStateException("Alias " + alias + " is missing from " + keystore
                        + " — delete the file to generate new development keys");
            }
            identities.add(new Identity(alias, key, x509));
        }
        return List.copyOf(identities);
    }

    private static void store(Path keystore, List<Identity> identities) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        for (Identity identity : identities) {
            ks.setKeyEntry(identity.alias(), identity.privateKey(), KEYSTORE_PASSWORD,
                    new Certificate[] {identity.certificate()});
        }
        Path dir = keystore.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir == null ? Path.of(".") : dir, "mock-token-", ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            ks.store(out, KEYSTORE_PASSWORD);
        }
        restrictToOwner(temp);
        try {
            Files.move(temp, keystore, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, keystore);
        }
    }

    /** Best effort: owner-only permissions (POSIX); Windows keeps the user-profile ACL. */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system (Windows): the file lives in the user profile
        }
    }
}
