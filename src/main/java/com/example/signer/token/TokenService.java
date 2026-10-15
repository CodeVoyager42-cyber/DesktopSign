package com.example.signer.token;

import com.example.signer.exception.ErrorCode;
import com.example.signer.signing.SigningRequest;
import com.example.signer.signing.SigningResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the application-level token lifecycle and state machine.
 *
 * <p>Dependent services interact with this class instead of with a concrete
 * token, keeping the whole application vendor-independent. All operations are
 * guarded by the strict transitions defined in {@link TokenStateMachine}.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final SigningTokenProvider tokenProvider;
    private final Object monitor = new Object();

    private TokenState state = TokenState.NO_TOKEN;
    private SigningToken currentToken;
    private List<SigningCertificate> cachedCertificates = List.of();

    public TokenService(SigningTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public TokenState getState() {
        synchronized (monitor) {
            return state;
        }
    }

    public boolean isAuthenticated() {
        synchronized (monitor) {
            return state == TokenState.AUTHENTICATED;
        }
    }

    public TokenInfo getTokenInfo() {
        synchronized (monitor) {
            requireActiveToken();
            return currentToken.getTokenInfo();
        }
    }

    public List<SigningCertificate> getCertificates() {
        synchronized (monitor) {
            requireActiveToken();
            return cachedCertificates;
        }
    }

    /**
     * Scans for an available token.
     *
     * <p>When a token is found while the flow is at {@code NO_TOKEN} or
     * {@code TOKEN_REMOVED}, drives the flow
     * {@code NO_TOKEN -> TOKEN_DETECTED -> TOKEN_INITIALIZED ->
     * CERTIFICATE_AVAILABLE}. When the provider reports no token while a token
     * is currently handled, the flow is torn down back to {@code NO_TOKEN}.
     * Calling {@code detect} while an already-connected token is still present
     * is a no-op.
     */
    public void detect() {
        synchronized (monitor) {
            Optional<SigningToken> available = tokenProvider.findAvailable();
            if (available.isEmpty()) {
                if (state != TokenState.NO_TOKEN) {
                    log.info("No token available; returning to NO_TOKEN");
                    transition(TokenState.TOKEN_REMOVED);
                    teardownTokenLocked();
                    transition(TokenState.NO_TOKEN);
                }
                return;
            }
            if (state != TokenState.NO_TOKEN && state != TokenState.TOKEN_REMOVED) {
                log.debug("Token already handled, ignoring detect()");
                return;
            }
            SigningToken token = available.get();
            currentToken = token;
            transition(TokenState.TOKEN_DETECTED);
            log.info("Token detected: {}", token.getTokenInfo().label());
            transition(TokenState.TOKEN_INITIALIZED);
            cachedCertificates = List.copyOf(token.getSigningCertificates());
            log.info("Discovered {} signing certificate(s)", cachedCertificates.size());
            transition(TokenState.CERTIFICATE_AVAILABLE);
        }
    }

    /**
     * Moves the flow to {@code WAITING_FOR_PIN} so the user can authenticate.
     *
     * <p>A no-op when the token session is already unlocked: an unlocked
     * token needs no new PIN entry, and retry paths (e.g. a re-run remote
     * signing attempt after a failed submission) replay this call.
     */
    public void startAuthentication() {
        synchronized (monitor) {
            if (sessionUnlocked()) {
                log.debug("Token session already unlocked; ignoring startAuthentication()");
                return;
            }
            requireState(TokenState.CERTIFICATE_AVAILABLE);
            transition(TokenState.WAITING_FOR_PIN);
            log.info("Waiting for token PIN");
        }
    }

    /**
     * Authenticates to the token using the given PIN.
     *
     * <p>The PIN is wiped immediately after use and is never stored.
     *
     * @throws TokenException on incorrect or locked PIN; the state stays
     *                        {@code WAITING_FOR_PIN} for wrong PINs, and moves
     *                        to {@code CERTIFICATE_AVAILABLE} when locked
     */
    public void authenticate(char[] pin) {
        Objects.requireNonNull(pin, "pin must not be null");
        synchronized (monitor) {
            if (sessionUnlocked()) {
                // The session was already authenticated earlier (e.g. a
                // retried flow): do not consume another PIN attempt.
                log.debug("Token session already unlocked; ignoring authenticate()");
                wipe(pin);
                return;
            }
            requireState(TokenState.WAITING_FOR_PIN);
            try {
                currentToken.authenticate(pin);
                transition(TokenState.AUTHENTICATED);
                log.info("Token authenticated");
            } catch (TokenException e) {
                if (e.getErrorCode() == ErrorCode.TOKEN_PIN_LOCKED) {
                    log.error("Token PIN locked: {}", e.getMessage());
                    transition(TokenState.CERTIFICATE_AVAILABLE);
                }
                throw e;
            } finally {
                wipe(pin);
            }
        }
    }

    /**
     * Cancels a pending PIN authentication and returns to
     * {@code CERTIFICATE_AVAILABLE}.
     */
    public void cancelAuthentication() {
        synchronized (monitor) {
            requireState(TokenState.WAITING_FOR_PIN);
            transition(TokenState.CERTIFICATE_AVAILABLE);
            log.info("PIN authentication cancelled");
        }
    }

    /**
     * Signs {@code data} with the token. Requires an authenticated session.
     */
    public SigningResult sign(SigningRequest request) {
        synchronized (monitor) {
            requireStateAny(TokenState.AUTHENTICATED,
                    TokenState.SIGNATURE_COMPLETE, TokenState.SIGNATURE_FAILED);
            transition(TokenState.SIGNING);
            try {
                byte[] signature = currentToken.sign(
                        request.keyAlias(), request.data(), request.algorithm());
                String signerSubject = findSubject(request.keyAlias());
                transition(TokenState.SIGNATURE_COMPLETE);
                log.info("Signature created for alias {}", request.keyAlias());
                return new SigningResult(signature, request.algorithm(), signerSubject);
            } catch (TokenException e) {
                if (e.getErrorCode() == ErrorCode.TOKEN_REMOVED
                        || e.getErrorCode() == ErrorCode.TOKEN_NOT_CONNECTED) {
                    log.error("Token vanished during signing: {}", e.getMessage());
                    teardownTokenLocked();
                    transition(TokenState.TOKEN_REMOVED);
                    transition(TokenState.NO_TOKEN);
                    throw e;
                }
                transition(TokenState.SIGNATURE_FAILED);
                throw e;
            }
        }
    }

    /**
     * Ends the authenticated session. Certificates remain available.
     */
    public void logout() {
        synchronized (monitor) {
            requireStateAny(TokenState.AUTHENTICATED,
                    TokenState.SIGNATURE_COMPLETE, TokenState.SIGNATURE_FAILED);
            currentToken.logout();
            transition(TokenState.CERTIFICATE_AVAILABLE);
            log.info("Token logged out");
        }
    }

    /**
     * Handles a token removal at any point of the flow. Safe to call
     * repeatedly.
     */
    public void disconnect() {
        synchronized (monitor) {
            if (state == TokenState.NO_TOKEN) {
                return;
            }
            transition(TokenState.TOKEN_REMOVED);
            teardownTokenLocked();
            transition(TokenState.NO_TOKEN);
            log.info("Token disconnected");
        }
    }

    private void teardownTokenLocked() {
        if (currentToken != null) {
            try {
                currentToken.logout();
            } catch (Exception e) {
                log.debug("Logout during teardown failed: {}", e.getMessage());
            }
            try {
                currentToken.close();
            } catch (Exception e) {
                log.debug("Close during teardown failed: {}", e.getMessage());
            }
            currentToken = null;
        }
        cachedCertificates = List.of();
    }

    private String findSubject(String keyAlias) {
        return cachedCertificates.stream()
                .filter(cert -> cert.getAlias().equals(keyAlias))
                .findFirst()
                .map(SigningCertificate::getSubject)
                .orElse("unknown");
    }

    private void transition(TokenState target) {
        state = TokenStateMachine.transition(state, target);
    }

    /** True when a PIN login is in effect for the current token session. */
    private boolean sessionUnlocked() {
        return state == TokenState.AUTHENTICATED
                || state == TokenState.SIGNATURE_COMPLETE
                || state == TokenState.SIGNATURE_FAILED;
    }

    private void requireState(TokenState expected) {
        if (state != expected) {
            throw new IllegalStateException(
                    "Operation requires state " + expected + " but current state is " + state);
        }
    }

    private void requireStateAny(TokenState... expected) {
        for (TokenState candidate : expected) {
            if (state == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
                "Operation requires one of " + List.of(expected) + " but current state is " + state);
    }

    private void requireActiveToken() {
        requireStateAny(TokenState.TOKEN_INITIALIZED, TokenState.CERTIFICATE_AVAILABLE,
                TokenState.WAITING_FOR_PIN, TokenState.AUTHENTICATED, TokenState.SIGNING,
                TokenState.SIGNATURE_COMPLETE, TokenState.SIGNATURE_FAILED);
    }

    private static void wipe(char[] pin) {
        if (pin != null) {
            Arrays.fill(pin, '\0');
        }
    }
}