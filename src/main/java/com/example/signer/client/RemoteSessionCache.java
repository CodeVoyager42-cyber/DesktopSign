package com.example.signer.client;

/**
 * Remembers the remote session used most recently during this app run
 * (brief §6/§43). When an emailed invitation arrives, the cached session is
 * offered to the backend silently and the SERVER decides whether that
 * identity may act as the recipient (brief §45/§81) — a refusal simply
 * falls through to the normal sign-in/registration branch.
 *
 * <p>Deliberately process-lifetime only: nothing is written to disk, so no
 * credential ever lands in a predictable file (brief §43). Cross-restart
 * persistence would require an OS credential store and is intentionally
 * deferred.
 */
public final class RemoteSessionCache {

    /** One remembered remote session: who, and the client that holds it. */
    public record Session(String username, WorkflowApiClient client) {
    }

    private static volatile Session current;

    /** Stores the most recent successful remote session (last wins). */
    public static void store(String username, WorkflowApiClient client) {
        if (username != null && !username.isBlank() && client != null) {
            current = new Session(username.trim(), client);
        }
    }

    /** The most recent session, or {@code null} when none exists yet. */
    public static Session current() {
        return current;
    }

    /** Forgets the cached session (logout flows). */
    public static void clear() {
        current = null;
    }

    private RemoteSessionCache() {
    }
}
