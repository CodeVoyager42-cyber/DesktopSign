package com.example.signer.token.mock;

import com.example.signer.token.SigningToken;
import com.example.signer.token.SigningTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Provides the in-memory mock token when it is "inserted".
 *
 * <p>Dev-only implementation of {@link SigningTokenProvider}. Active only while
 * PKCS#11 is disabled ({@code signature.pkcs11.enabled=false}, the default),
 * so exactly one {@link SigningTokenProvider} exists in the context.
 */
@Component
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