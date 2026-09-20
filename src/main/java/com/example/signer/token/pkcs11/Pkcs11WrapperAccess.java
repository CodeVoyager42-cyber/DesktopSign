package com.example.signer.token.pkcs11;

import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenInfo;
import sun.security.pkcs11.wrapper.CK_ATTRIBUTE;
import sun.security.pkcs11.wrapper.CK_TOKEN_INFO;
import sun.security.pkcs11.wrapper.PKCS11;
import sun.security.pkcs11.wrapper.PKCS11Constants;
import sun.security.pkcs11.wrapper.PKCS11Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Bridge to the JDK-internal PKCS#11 wrapper ({@code sun.security.pkcs11.wrapper}).
 *
 * <p>Used ONLY for public, pre-authentication reads: token metadata and the
 * signing certificates, which the {@code SunPKCS11} JCE provider refuses to
 * expose before login ({@code CKF_LOGIN_REQUIRED} tokens). Login, logout and
 * signing stay on the public JCE API ({@code AuthProvider}, {@code KeyStore},
 * {@code Signature}).
 *
 * <p>This class never calls {@code C_Login} and never touches private key
 * material. The underlying native module is shared with the JCE provider:
 * {@link PKCS11#getInstance(String, String, Object, boolean)} returns the same
 * cached instance that {@code SunPKCS11} initialized (keyed by library path),
 * so no second {@code C_Initialize} happens.
 *
 * <p>Backed by JDK 21 internals; compiling/running this module requires
 * {@code --add-exports jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED}
 * (already wired in the Maven build for compiler, tests and JavaFX run).
 */
final class Pkcs11WrapperAccess implements AutoCloseable {

    private static final String FUNCTION_LIST = "C_GetFunctionList";

    private static final long CKR_SLOT_ID_INVALID = 0x00000003L;
    private static final long CKR_ATTRIBUTE_TYPE_INVALID = 0x00000012L;

    private final PKCS11 p11;
    private final int slotListIndex;

    private Pkcs11WrapperAccess(PKCS11 p11, int slotListIndex) {
        this.p11 = p11;
        this.slotListIndex = slotListIndex;
    }

    /**
     * Obtains the shared PKCS#11 module instance for the given library. The
     * caller must have configured {@code SunPKCS11} for the same library first,
     * otherwise an uninitialized (and unusable) module would be cached.
     */
    static Pkcs11WrapperAccess create(String libraryPath, int slotListIndex)
            throws IOException, PKCS11Exception {
        PKCS11 p11 = PKCS11.getInstance(libraryPath, FUNCTION_LIST, null, true);
        return new Pkcs11WrapperAccess(p11, slotListIndex);
    }

    /**
     * Resolves the configured slot the same way {@code SunPKCS11} does:
     * {@code slotListIndex} indexes the list of ALL slots. See
     * {@code SunPKCS11.createToken}.
     */
    long resolveSlotId() throws PKCS11Exception {
        long[] slots = p11.C_GetSlotList(false);
        if (slotListIndex < 0 || slotListIndex >= slots.length) {
            throw new PKCS11Exception(CKR_SLOT_ID_INVALID,
                    " slotListIndex=" + slotListIndex + " but " + slots.length + " slots present");
        }
        return slots[slotListIndex];
    }

    /** Reads the safe, public token metadata for the given slot. */
    TokenInfo readTokenInfo(long slotId) throws PKCS11Exception {
        CK_TOKEN_INFO info = p11.C_GetTokenInfo(slotId);
        return new TokenInfo(
                trimmed(info.label),
                trimmed(info.manufacturerID),
                trimmed(info.model),
                trimmed(info.serialNumber));
    }

    /**
     * Enumerates the public certificates present on the token without any
     * authentication, returning each with the alias of the private key it is
     * paired with (same alias scheme as {@code P11KeyStore}: {@code CKA_LABEL}
     * preferred, {@code CKA_ID} hex otherwise).
     */
    List<SigningCertificate> readSigningCertificates(long slotId) {
        List<SigningCertificate> certs = new ArrayList<>();
        long session = 0;
        try {
            session = p11.C_OpenSession(slotId, PKCS11Constants.CKF_SERIAL_SESSION, null, null);
            CK_ATTRIBUTE[] template = {
                    new CK_ATTRIBUTE(PKCS11Constants.CKA_CLASS, PKCS11Constants.CKO_CERTIFICATE) };
            p11.C_FindObjectsInit(session, template);
            long[] batch;
            int ordinal = 0;
            do {
                batch = p11.C_FindObjects(session, 8);
                for (long handle : batch) {
                    X509Certificate certificate = readCertificate(session, handle);
                    String alias = resolveAlias(session, handle, ++ordinal);
                    certs.add(new SigningCertificate(alias, certificate));
                }
            } while (batch.length > 0);
            p11.C_FindObjectsFinal(session);
        } catch (PKCS11Exception e) {
            throw new Pkcs11NativeException("Could not enumerate token certificates", e);
        } finally {
            if (session != 0) {
                try {
                    p11.C_CloseSession(session);
                } catch (PKCS11Exception ignored) {
                    // session already closed by the module
                }
            }
        }
        return certs;
    }

    @Override
    public void close() {
        // Nothing to finalize: the native module is owned by the JCE provider
        // and stays cached for the lifetime of the JVM.
    }

    private static final class Pkcs11NativeException extends RuntimeException {
        Pkcs11NativeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private X509Certificate readCertificate(long session, long handle) throws PKCS11Exception {
        CK_ATTRIBUTE[] attrs = { new CK_ATTRIBUTE(PKCS11Constants.CKA_VALUE) };
        p11.C_GetAttributeValue(session, handle, attrs);
        byte[] der = attrs[0].getByteArray();
        if (der == null) {
            throw new PKCS11Exception(CKR_ATTRIBUTE_TYPE_INVALID,
                    " certificate value unavailable");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new Pkcs11NativeException("Cannot parse certificate from token", e);
        }
    }

    private String resolveAlias(long session, long handle, int ordinal) {
        String label = readStringAttribute(session, handle, PKCS11Constants.CKA_LABEL);
        if (label != null && !label.isBlank()) {
            return label;
        }
        byte[] id = readByteAttribute(session, handle, PKCS11Constants.CKA_ID);
        if (id != null && id.length > 0) {
            return "id-" + HexFormat.of().formatHex(id);
        }
        return "certificate-" + ordinal;
    }

    private String readStringAttribute(long session, long handle, long type) {
        try {
            CK_ATTRIBUTE[] attrs = { new CK_ATTRIBUTE(type) };
            p11.C_GetAttributeValue(session, handle, attrs);
            char[] chars = attrs[0].getCharArray();
            return chars == null ? null : new String(chars);
        } catch (PKCS11Exception e) {
            return null;
        }
    }

    private byte[] readByteAttribute(long session, long handle, long type) {
        try {
            CK_ATTRIBUTE[] attrs = { new CK_ATTRIBUTE(type) };
            p11.C_GetAttributeValue(session, handle, attrs);
            return attrs[0].getByteArray();
        } catch (PKCS11Exception e) {
            return null;
        }
    }

    private static String trimmed(char[] raw) {
        if (raw == null) {
            return "";
        }
        int end = raw.length;
        while (end > 0 && (raw[end - 1] == '\0' || raw[end - 1] == ' ')) {
            end--;
        }
        return new String(raw, 0, end);
    }
}