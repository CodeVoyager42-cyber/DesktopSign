package com.example.signer.token;

import java.util.Optional;

/**
 * Locates the currently available {@link SigningToken}.
 *
 * <p>Token sources are pluggable: a mock implementation is used in
 * development, a PKCS#11 implementation will scan physical slots later.
 * Application services depend on this interface instead of on a concrete
 * token.
 */
public interface SigningTokenProvider {

    /**
     * @return the available signing token, or empty if no token is connected
     */
    Optional<SigningToken> findAvailable();
}