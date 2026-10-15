package com.example.signer.workflow;

import java.util.Locale;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Who may hold the next signing turn.
 *
 * <p>Selected by the workflow creator and enforced by the server — never by
 * the client. The same value travels inside the portable PDF manifest so the
 * local signing domain enforces the identical rule offline.
 */
public enum WorkflowSigningPolicy {

    /**
     * Fixed order: signer 1, then 2, then 3 — each signer's turn begins only
     * when the previous signer's signature has been accepted. Out-of-order
     * signatures are rejected as {@code NOT_YOUR_TURN}.
     */
    @JsonProperty("SEQUENTIAL")
    SEQUENTIAL,

    /**
     * Order-free: every still-pending signer may claim the next turn; the
     * server atomically reserves the turn for exactly one of them (409 for
     * the losers). The winner must submit their signature before another
     * signer can claim. When no turn is claimed, "current signer" is
     * undefined — anyone pending may initiate a claim.
     */
    @JsonProperty("FLEXIBLE")
    FLEXIBLE;

    /** Parses the JSON/config representation; {@code null} falls back to sequential. */
    public static WorkflowSigningPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return SEQUENTIAL;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "FLEXIBLE" -> FLEXIBLE;
            case "SEQUENTIAL" -> SEQUENTIAL;
            default -> throw new IllegalArgumentException(
                    "Unknown signing policy: " + value
                    + " (expected SEQUENTIAL or FLEXIBLE)");
        };
    }
}
