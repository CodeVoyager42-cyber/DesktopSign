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

    /** One participant's own state on a task ({@code ParticipantStateView.myStateLabel}). */
    public enum ParticipantState {
        /** This slot's signature was accepted. */
        SIGNED,
        /** The participant must register their signing certificate first. */
        CERTIFICATE_PENDING,
        /**
         * The slot names an account that does not exist. Still produced:
         * {@code RemoteWorkflowService} reports it for a slot whose user is
         * not registered. Nobody is invited any more, so it means "cannot
         * sign yet", not "an invitation is outstanding".
         */
        INVITED,
        /** The requester cancelled the workflow. */
        CANCELLED,
        /** The workflow failed and needs attention. */
        BLOCKED,
        /** It is this participant's turn to sign. */
        YOUR_TURN,
        /** Any-order workflow: the participant may claim the turn. */
        WAITING_FOR_CLAIM,
        WAITING_FOR_OTHERS,
        IN_PROGRESS,
        /** The slot was withdrawn or replaced. */
        REVOKED,
        DECLINED,
        EXPIRED,
        UNKNOWN;

        public static ParticipantState parse(String raw) {
            return ApiStates.parse(ParticipantState.class, raw, UNKNOWN);
        }
    }

    /** A signer slot in the signing order ({@code TaskParticipantView.state}). */
    public enum SlotState {
        SIGNED,
        /** Holds the turn now. */
        CURRENT,
        WAITING,
        UNKNOWN;

        public static SlotState parse(String raw) {
            return ApiStates.parse(SlotState.class, raw, UNKNOWN);
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
