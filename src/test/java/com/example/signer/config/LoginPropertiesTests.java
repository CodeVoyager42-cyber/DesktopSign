package com.example.signer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the start-up login gate credentials.
 */
class LoginPropertiesTests {

    @Test
    void defaultsAreIsicodForBothFields() {
        LoginProperties props = new LoginProperties();
        assertThat(props.getUsername()).isEqualTo("ISICOD");
        assertThat(props.getPassword()).isEqualTo("ISICOD");
    }

    @Test
    void matchingCredentialsAreAccepted() {
        LoginProperties props = new LoginProperties();
        assertThat(props.matches("ISICOD", "ISICOD")).isTrue();
    }

    @Test
    void wrongUsernameOrPasswordIsRejected() {
        LoginProperties props = new LoginProperties();
        assertThat(props.matches("isicod", "ISICOD")).isFalse(); // case matters
        assertThat(props.matches("ISICOD", "wrong")).isFalse();
        assertThat(props.matches("wrong", "ISICOD")).isFalse();
    }

    @Test
    void nullInputIsRejectedWithoutThrowing() {
        LoginProperties props = new LoginProperties();
        assertThat(props.matches(null, "ISICOD")).isFalse();
        assertThat(props.matches("ISICOD", null)).isFalse();
        assertThat(props.matches(null, null)).isFalse();
    }

    @Test
    void settersAllowExternalConfiguration() {
        LoginProperties props = new LoginProperties();
        props.setUsername("alice");
        props.setPassword("wonderland");
        assertThat(props.matches("alice", "wonderland")).isTrue();
        assertThat(props.matches("alice", "ISICOD")).isFalse();
    }
}
