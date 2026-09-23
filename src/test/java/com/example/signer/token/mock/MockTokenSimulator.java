package com.example.signer.token.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development helper that lets a user (or a test) simulate inserting and
 * removing the mock token without any hardware.
 */
@Component
public class MockTokenSimulator {

    private static final Logger log = LoggerFactory.getLogger(MockTokenSimulator.class);

    private final MockSigningToken token;

    public MockTokenSimulator(MockSigningToken token) {
        this.token = token;
    }

    /**
     * Simulates connecting the USB token.
     *
     * @return true if the token was inserted, false if it already was present
     */
    public boolean insertToken() {
        boolean changed = !token.isPresent();
        token.setPresent(true);
        if (changed) {
            log.info("Mock token inserted");
        }
        return changed;
    }

    /**
     * Simulates disconnecting the USB token.
     *
     * @return true if the token was removed, false if it was not present
     */
    public boolean removeToken() {
        boolean changed = token.isPresent();
        token.setPresent(false);
        if (changed) {
            log.info("Mock token removed");
        }
        return changed;
    }

    public boolean isTokenInserted() {
        return token.isPresent();
    }
}