package com.example.signer.token;

import com.example.signer.exception.ErrorCode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@link SigningTokenProvider} of a NON-development context in which no
 * PKCS#11 token is configured.
 *
 * <p>There is deliberately no fallback to the development mock token outside
 * the {@code dev} profile. The coordination server never signs and never
 * asks for a token, so it can run with this provider; the desktop
 * application refuses to start in that configuration
 * ({@code ProductionSafetyValidator}), and should anything still ask for a
 * token, it gets a clear configuration error instead of a mock signature.
 */
@Component
@Profile("!dev")
@ConditionalOnProperty(name = "signature.pkcs11.enabled", havingValue = "false", matchIfMissing = true)
public class UnconfiguredSigningTokenProvider implements SigningTokenProvider {

    /** User-facing explanation of the missing configuration. */
    public static final String MESSAGE = "No signing token is configured. Set"
            + " SIGNATURE_PKCS11_ENABLED=true and PKCS11_LIBRARY to your token's"
            + " PKCS#11 library (the development mock token is only available in"
            + " the 'dev' profile).";

    @Override
    public Optional<SigningToken> findAvailable() {
        throw new TokenException(ErrorCode.PKCS11_LIBRARY_NOT_FOUND, MESSAGE);
    }
}
