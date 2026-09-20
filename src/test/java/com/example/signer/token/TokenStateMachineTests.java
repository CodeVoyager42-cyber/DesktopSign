package com.example.signer.token;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenStateMachineTests {

    @Test
    void allowsDocumentedTransition() {
        TokenState result = TokenStateMachine.transition(TokenState.NO_TOKEN, TokenState.TOKEN_DETECTED);
        assertThat(result).isEqualTo(TokenState.TOKEN_DETECTED);
    }

    @Test
    void rejectsUndocumentedTransition() {
        assertThatThrownBy(() ->
                TokenStateMachine.transition(TokenState.NO_TOKEN, TokenState.AUTHENTICATED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid token state transition");
    }

    @Test
    void canTransitionReflectsTheDeclaredMap() {
        assertThat(TokenStateMachine.canTransition(TokenState.WAITING_FOR_PIN, TokenState.AUTHENTICATED))
                .isTrue();
        assertThat(TokenStateMachine.canTransition(TokenState.WAITING_FOR_PIN, TokenState.SIGNING))
                .isFalse();
    }

    @Test
    void everyNonConnectedStateCanBeRemoved() {
        for (TokenState state : TokenState.values()) {
            if (state == TokenState.NO_TOKEN || state == TokenState.TOKEN_REMOVED) {
                continue;
            }
            assertThat(TokenStateMachine.canTransition(state, TokenState.TOKEN_REMOVED))
                    .as("state %s should be able to transition to TOKEN_REMOVED", state)
                    .isTrue();
        }
    }
}