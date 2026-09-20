package com.example.signer.signing;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Default signing service delegating to the {@link TokenService} and the
 * generic token abstraction.
 */
@Service
public class DefaultSigningService implements SigningService {

    private final TokenService tokenService;

    public DefaultSigningService(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public SigningResult sign(SigningRequest request) {
        Objects.requireNonNull(request, "signing request must not be null");
        TokenState state = tokenService.getState();
        if (state != TokenState.AUTHENTICATED
                && state != TokenState.SIGNATURE_COMPLETE
                && state != TokenState.SIGNATURE_FAILED) {
            throw new SignerException(ErrorCode.TOKEN_LOGIN_FAILED,
                    "Signing requires an authenticated token. Current state: " + state);
        }
        return tokenService.sign(request);
    }
}