package com.example.signer.server.api;

/**
 * The state vocabularies the REST API speaks, as enums.
 *
 * <p>On the wire these stay plain strings (the enum {@code name()}), so older
 * clients and servers keep working. In code, both sides use the enums
 * instead of string literals: a typo becomes a compile error, and a
 * {@code switch} lists every case. {@code parse} never throws — a value this
 * build does not know (a newer server) maps to {@code UNKNOWN}.
 */
public final class ApiStates {

    private ApiStates() {
    }

    /** The signer's own state on a task ({@code ParticipantStateView.myStateLabel}). */
    public enum ParticipantState {
        /** The signature was accepted. */
        SIGNED,
        /** The signer must register their signing certificate first. */
        CERTIFICATE_PENDING,
        /** The owner cancelled the workflow. */
        CANCELLED,
        /** The workflow failed and needs attention. */
        BLOCKED,
        /** The document is ready and waiting to be signed. */
        YOUR_TURN,
        IN_PROGRESS,
        UNKNOWN;

        public static ParticipantState parse(String raw) {
            return ApiStates.parse(ParticipantState.class, raw, UNKNOWN);
        }
    }

    static <E extends Enum<E>> E parse(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
