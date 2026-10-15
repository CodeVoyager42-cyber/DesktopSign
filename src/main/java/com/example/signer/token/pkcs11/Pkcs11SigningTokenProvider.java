package com.example.signer.token.pkcs11;

import com.example.signer.config.Pkcs11Properties;
import com.example.signer.exception.ErrorCode;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.SigningToken;
import com.example.signer.token.SigningTokenProvider;
import com.example.signer.token.TokenException;
import com.example.signer.token.TokenInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AuthProvider;
import java.security.Provider;
import java.security.Security;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds a real PKCS#11 hardware token through the {@code SunPKCS11} provider.
 *
 * <p>Active only when {@code signature.pkcs11.enabled=true}; the mock provider
 * is disabled at the same time, so exactly one {@link SigningTokenProvider}
 * exists in the application context.
 *
 * <p>The SunPKCS11 configuration file is rendered from
 * {@link Pkcs11Properties} into a temporary file (the JCE expects a file path,
 * not inline config) and {@code SunPKCS11.configure(...)} is used both as the
 * token-presence check (it fails when the slot is empty) and as the origin of
 * the shared native module instance used for the wrapper reads. No PIN ever
 * appears in the configuration.
 */
@Component
@ConditionalOnProperty(name = "signature.pkcs11.enabled", havingValue = "true")
public class Pkcs11SigningTokenProvider implements SigningTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(Pkcs11SigningTokenProvider.class);

    private final Pkcs11Properties properties;

    public Pkcs11SigningTokenProvider(Pkcs11Properties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized Optional<SigningToken> findAvailable() {
        String library = properties.getLibrary();
        if (library.isBlank()) {
            throw new TokenException(ErrorCode.PKCS11_LIBRARY_NOT_FOUND,
                    "signature.pkcs11.enabled is true but signature.pkcs11.library is not set");
        }
        if (isConfiguredPath(library) && !Files.exists(Path.of(library))) {
            throw new TokenException(ErrorCode.PKCS11_LIBRARY_NOT_FOUND,
                    "PKCS#11 library does not exist: " + library);
        }
        Path config = writeConfig(library);
        try {
            Provider base = Security.getProvider("SunPKCS11");
            Provider configured = base.configure(config.toString());
            AuthProvider authProvider = (AuthProvider) configured;
            try (Pkcs11WrapperAccess access = Pkcs11WrapperAccess.create(library,
                    properties.getSlotListIndex())) {
                long slotId = access.resolveSlotId();
                TokenInfo tokenInfo = access.readTokenInfo(slotId);
                List<SigningCertificate> certificates = access.readSigningCertificates(slotId);
                if (certificates.isEmpty()) {
                    log.warn("PKCS#11 token '{}' present but has no signing certificates",
                            tokenInfo.label());
                    return Optional.empty();
                }
                log.info("PKCS#11 token detected: {} ({} signing certificate(s))",
                        tokenInfo.label(), certificates.size());
                return Optional.of(
                        new Pkcs11SigningToken(authProvider, tokenInfo, certificates));
            }
        } catch (TokenException e) {
            throw e;
        } catch (IllegalAccessError e) {
            // The JVM was started without the module export the certificate
            // reads need: every detect would fail, so say so instead of
            // reporting "no card".
            log.error("PKCS#11 wrapper not accessible: start Java with "
                    + "--add-exports jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED");
            throw new TokenException(ErrorCode.PKCS11_INITIALIZATION_FAILED,
                    "SignFlow was started without smart-card support enabled in Java "
                            + "(missing --add-exports for sun.security.pkcs11.wrapper)", e);
        } catch (LinkageError e) {
            log.error("PKCS#11 library could not be loaded: {}", e.toString());
            throw new TokenException(ErrorCode.PKCS11_INITIALIZATION_FAILED,
                    "The smart card driver could not be loaded: " + library, e);
        } catch (Exception e) {
            // Usually "no card in the reader"; the reason is logged so a
            // driver or slot problem is visible without debug logging.
            log.info("PKCS#11 token not available: {}", rootMessage(e));
            log.debug("PKCS#11 token lookup failure", e);
            return Optional.empty();
        } finally {
            deleteQuietly(config);
        }
    }

    private Path writeConfig(String library) {
        String name = "SunPKCS11-Signer-" + UUID.randomUUID().toString().replace("-", "");
        // The SunPKCS11 config parser treats backslashes as escape-ish tokens,
        // so render Windows paths with forward slashes (absolute on all targets).
        String content = """
                name = %s
                library = %s
                slotListIndex = %d
                """.formatted(name, library.replace('\\', '/'),
                properties.getSlotListIndex());
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pdf-signer");
            Files.createDirectories(dir);
            Path file = Files.createTempFile(dir, "pkcs11-", ".cfg");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new TokenException(ErrorCode.PKCS11_INITIALIZATION_FAILED,
                    "Could not write SunPKCS11 configuration", e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? e.toString() : e.getClass().getSimpleName() + " <- " + root;
    }

    private static boolean isConfiguredPath(String library) {
        return library.contains("/") || library.contains("\\");
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort cleanup of temp config
        }
    }
}