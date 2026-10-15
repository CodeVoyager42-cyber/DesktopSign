package com.example.signer.remote;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invitation fast path (brief §6 Scenario 1) rides on this cache: the
 * most recent remote session of the run is offered to the backend when an
 * emailed invitation arrives, and the SERVER decides identity. The cache
 * must stay process-lifetime only — nothing is ever written to disk.
 */
class RemoteSessionCacheTests {

    @Test
    void emptyByDefault() {
        assertThat(RemoteSessionCache.current()).isNull();
    }

    @Test
    void storesAndReturnsTheLatestSession() {
        try {
            RemoteSessionCache.store("jane", null); // ignored: no client
            assertThat(RemoteSessionCache.current()).isNull();

            RemoteSessionCache.store("jane", new Object() == null
                    ? null : newClient());
            RemoteSessionCache.Session first = RemoteSessionCache.current();
            assertThat(first).isNotNull();
            assertThat(first.username()).isEqualTo("jane");

            RemoteSessionCache.store("bob", newClient());
            assertThat(RemoteSessionCache.current().username()).isEqualTo("bob");
        } finally {
            RemoteSessionCache.clear();
        }
    }

    @Test
    void clearForgetsEverything() {
        RemoteSessionCache.store("jane", newClient());
        RemoteSessionCache.clear();
        assertThat(RemoteSessionCache.current()).isNull();
    }

    private WorkflowApiClient newClient() {
        // The cache never dereferences the client in tests; a minimal real
        // instance with dummy tokens keeps the type contract honest without
        // any network traffic.
        return WorkflowApiClient.withSession("http://localhost:8080/", "u",
                new com.example.signer.server.api.ApiContracts.TokenPairResponse(
                        "dummy-access", 60, "dummy-refresh"));
    }
}
