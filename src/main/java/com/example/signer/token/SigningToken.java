package com.example.signer.token;

import com.example.signer.token.mock.MockSigningToken;

/**
 * Generic, vendor-independent cryptographic token.
 *
 * <p>The concrete token performs all private-key operations internally; the
 * private key never leaves the token. Implementations are expected to keep
 * the signing key inside hardware (a PKCS#11 implementation will be added in a
 * later phase) or, for development only, inside an in-memory mock
 * ({@link MockSigningToken}).
 *
 * <p>The PDF-signing layer must depend on this interface and never on a
 * specific vendor.
 */
public interface SigningToken {

    /**
     * Safe, public token metadata. Throws {@link TokenException} when the
     * token is not available.
     */
    TokenInfo getTokenInfo();

    /**
     * Certificates available for signing on this token, including the alias of
     * the private key each certificate is paired with.
     */
    java.util.List<SigningCertificate> getSigningCertificates();

    /**
     * Authenticates to the token with the user PIN.
     *
     * <p>The PIN is consumed by this call and must be wiped by the caller
     * afterwards.
     *
     * @throws TokenException with {@link com.example.signer.exception.ErrorCode#TOKEN_PIN_INCORRECT}
     *                        or {@link com.example.signer.exception.ErrorCode#TOKEN_PIN_LOCKED}
     *                        on authentication failure
     */
    void authenticate(char[] pin);

    /**
     * Asks the token to sign {@code data} with the private key referenced by
     * {@code keyAlias}. The private key never leaves the token.
     */
    byte[] sign(String keyAlias, byte[] data, SigningAlgorithm algorithm);

    /**
     * Ends the authenticated session. The token remains available for a new
     * authentication.
     */
    void logout();

    /**
     * Closes the token session and releases resources. Safe to call multiple
     * times.
     */
    void close();
}