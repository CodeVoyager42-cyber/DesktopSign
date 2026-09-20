package com.example.signer.token;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates token state transitions.
 *
 * <p>Transitions that are not declared here are rejected with an
 * {@link IllegalStateException}, which guards the orchestration layer against
 * corrupting the state machine.
 */
public final class TokenStateMachine {

    private static final Map<TokenState, Set<TokenState>> ALLOWED_TRANSITIONS =
            new EnumMap<>(TokenState.class);

    static {
        ALLOWED_TRANSITIONS.put(TokenState.NO_TOKEN,
                EnumSet.of(TokenState.TOKEN_DETECTED));
        ALLOWED_TRANSITIONS.put(TokenState.TOKEN_DETECTED,
                EnumSet.of(TokenState.TOKEN_INITIALIZED, TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.TOKEN_INITIALIZED,
                EnumSet.of(TokenState.CERTIFICATE_AVAILABLE, TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.CERTIFICATE_AVAILABLE,
                EnumSet.of(TokenState.WAITING_FOR_PIN, TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.WAITING_FOR_PIN,
                EnumSet.of(TokenState.AUTHENTICATED, TokenState.CERTIFICATE_AVAILABLE,
                        TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.AUTHENTICATED,
                EnumSet.of(TokenState.SIGNING, TokenState.CERTIFICATE_AVAILABLE,
                        TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.SIGNING,
                EnumSet.of(TokenState.SIGNATURE_COMPLETE, TokenState.SIGNATURE_FAILED,
                        TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.SIGNATURE_COMPLETE,
                EnumSet.of(TokenState.SIGNING, TokenState.CERTIFICATE_AVAILABLE,
                        TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.SIGNATURE_FAILED,
                EnumSet.of(TokenState.SIGNING, TokenState.CERTIFICATE_AVAILABLE,
                        TokenState.TOKEN_REMOVED));
        ALLOWED_TRANSITIONS.put(TokenState.TOKEN_REMOVED,
                EnumSet.of(TokenState.TOKEN_DETECTED, TokenState.NO_TOKEN));
    }

    private TokenStateMachine() {
    }

    /**
     * Returns the target state if the transition {@code from -> to} is valid.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public static TokenState transition(TokenState from, TokenState to) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(
                    "Invalid token state transition: " + from + " -> " + to);
        }
        return to;
    }

    /**
     * True if the transition {@code from -> to} is declared.
     */
    public static boolean canTransition(TokenState from, TokenState to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}