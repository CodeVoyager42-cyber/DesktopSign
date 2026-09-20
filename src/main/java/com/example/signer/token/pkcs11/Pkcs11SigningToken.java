package com.example.signer.token.pkcs11;

import com.example.signer.exception.ErrorCode;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.SigningToken;
import com.example.signer.token.TokenException;
import com.example.signer.token.TokenInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.pkcs11.wrapper.PKCS11Exception;
import sun.security.pkcs11.wrapper.PKCS11Exception.RV;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import java.security.AuthProvider;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Signature;
import java.util.List;

/**
 * PKCS#11 hardware token backed by the {@code SunPKCS11} JCE provider.
 *
 * <p>Token metadata and certificates are collected before any authentication
 * through {@link Pkcs11WrapperAccess}; authentication uses the public
 * {@link AuthProvider#login(Subject, CallbackHandler)} JCE API, and signing
 * goes through {@link KeyStore} + {@link Signature} with the token-held private
 * key, which never leaves the device.
 *
 * <p>The private key never leaves the token. Wrong PINs map to
 * {@link ErrorCode#TOKEN_PIN_INCORRECT}, a locked PIN to
 * {@link ErrorCode#TOKEN_PIN_LOCKED} (per the underlying
 * {@code CKR_PIN_INCORRECT}/{@code CKR_PIN_LOCKED} return codes).
 */
final class Pkcs11SigningToken implements SigningToken {

    private static final Logger log = LoggerFactory.getLogger(Pkcs11SigningToken.class);

    private final Provider provider;
    private final AuthProvider authProvider;
    private final TokenInfo tokenInfo;
    private final List<SigningCertificate> certificates;

    private volatile boolean authenticated;
    private volatile KeyStore keyStore;

    Pkcs11SigningToken(AuthProvider authProvider, TokenInfo tokenInfo,
                       List<SigningCertificate> certificates) {
        this.authProvider = authProvider;
        this.provider = authProvider;
        this.tokenInfo = tokenInfo;
        this.certificates = List.copyOf(certificates);
    }

    @Override
    public TokenInfo getTokenInfo() {
        return tokenInfo;
    }

    @Override
    public List<SigningCertificate> getSigningCertificates() {
        return certificates;
    }

    @Override
    public void authenticate(char[] pin) {
        if (pin == null) {
            throw new TokenException(ErrorCode.TOKEN_LOGIN_FAILED, "No PIN provided");
        }
        if (authenticated) {
            return;
        }
        CallbackHandler handler = callbacks -> {
            for (Callback callback : callbacks) {
                if (callback instanceof PasswordCallback passwordCallback) {
                    passwordCallback.setPassword(pin);
                }
            }
        };
        try {
            authProvider.login(null, handler);
        } catch (FailedLoginException e) {
            throw toTokenException(e);
        } catch (LoginException e) {
            throw toTokenException(e);
        }
        loadKeyStore();
        authenticated = true;
        log.info("PKCS#11 token authenticated");
    }

    @Override
    public byte[] sign(String keyAlias, byte[] data, SigningAlgorithm algorithm) {
        if (!authenticated || keyStore == null) {
            throw new TokenException(ErrorCode.TOKEN_LOGIN_FAILED,
                    "Token is not authenticated");
        }
        PrivateKey key = resolvePrivateKey(keyAlias);
        try {
            Signature signature = Signature.getInstance(algorithm.getJcaName(), provider);
            signature.initSign(key);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            if (isTokenRemoved(e)) {
                throw new TokenException(ErrorCode.TOKEN_REMOVED,
                        "Token was removed during signing", e);
            }
            log.error("PKCS#11 signing operation failed for alias {}", keyAlias, e);
            throw new TokenException(ErrorCode.TOKEN_SESSION_FAILED,
                    "Signing operation failed", e);
        }
    }

    @Override
    public void logout() {
        if (!authenticated) {
            return;
        }
        try {
            authProvider.logout();
        } catch (LoginException e) {
            log.debug("Logout failed (likely token removed): {}", e.getMessage());
        } finally {
            authenticated = false;
        }
    }

    @Override
    public void close() {
        logout();
    }

    private void loadKeyStore() {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS11", provider);
            // Session is already logged in via AuthProvider.login, so the
            // password-less load only maps the token aliases to entries.
            ks.load(null, null);
            this.keyStore = ks;
        } catch (Exception e) {
            try {
                authProvider.logout();
            } catch (LoginException ignored) {
                // best effort
            }
            authenticated = false;
            log.error("PKCS#11 keystore alias mapping failed", e);
            throw new TokenException(ErrorCode.TOKEN_SESSION_FAILED,
                    "Failed to read token key aliases", e);
        }
    }

    private PrivateKey resolvePrivateKey(String keyAlias) {
        try {
            PrivateKey key = (PrivateKey) keyStore.getKey(keyAlias, null);
            if (key == null) {
                throw new TokenException(ErrorCode.PRIVATE_KEY_NOT_AVAILABLE,
                        "No private key found for alias: " + keyAlias);
            }
            return key;
        } catch (TokenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("No private key available for alias {}", keyAlias);
            throw new TokenException(ErrorCode.PRIVATE_KEY_NOT_AVAILABLE,
                    "No private key found for alias: " + keyAlias, e);
        }
    }

    private static TokenException toTokenException(LoginException e) {
        PKCS11Exception cause = findPkcs11Cause(e);
        if (cause != null) {
            if (cause.match(RV.CKR_PIN_LOCKED)) {
                return new TokenException(ErrorCode.TOKEN_PIN_LOCKED,
                        "Token PIN locked. Re-insert the token to continue.", e);
            }
            if (cause.match(RV.CKR_PIN_INCORRECT)) {
                return new TokenException(ErrorCode.TOKEN_PIN_INCORRECT,
                        "Incorrect token PIN", e);
            }
        }
        return new TokenException(ErrorCode.TOKEN_LOGIN_FAILED,
                "Token authentication failed", e);
    }

    private static boolean isTokenRemoved(Throwable t) {
        PKCS11Exception cause = findPkcs11Cause(t);
        if (cause == null) {
            return false;
        }
        return cause.match(RV.CKR_DEVICE_REMOVED)
                || cause.match(RV.CKR_SESSION_CLOSED)
                || cause.match(RV.CKR_TOKEN_NOT_PRESENT);
    }

    private static PKCS11Exception findPkcs11Cause(Throwable t) {
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof PKCS11Exception pkcs11Exception) {
                return pkcs11Exception;
            }
            cursor = cursor.getCause();
        }
        return null;
    }
}