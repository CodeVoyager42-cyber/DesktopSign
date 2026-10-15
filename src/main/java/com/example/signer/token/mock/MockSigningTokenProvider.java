package com.example.signer.token.mock;

import com.example.signer.token.UnconfiguredSigningTokenProvider;
import com.example.signer.token.SigningToken;
import com.example.signer.token.SigningTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Provides the in-memory mock token when it is "inserted".
 *
 * <p>DEVELOPMENT-ONLY implementation of {@link SigningTokenProvider}: active
 * only in the {@code dev} profile AND while PKCS#11 is disabled
 * ({@code signature.pkcs11.enabled=false}), so exactly one
 * {@link SigningTokenProvider} exists in the context. Outside {@code dev}
 * there is no silent fallback to mock signing: see
 * {@link UnconfiguredSigningTokenProvider}.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "signature.pkcs11.enabled", havingValue = "false", matchIfMissing = true)
public class MockSigningTokenProvider implements SigningTokenProvider {

    private final MockSigningToken token;

    public MockSigningTokenProvider(MockSigningToken token) {
        this.token = token;
    }

    @Override
    public Optional<SigningToken> findAvailable() {
        if (token.isPresent()) {
            return Optional.of(token);
        }
        return Optional.empty();
    }
}