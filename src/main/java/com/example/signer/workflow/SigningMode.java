package com.example.signer.workflow;

import java.util.List;

/**
 * How many people will sign one document.
 *
 * <p>The enum deliberately carries no behaviour beyond the signer count, so
 * adding a mode (e.g. {@code FOUR_SIGNERS} or a future {@code CUSTOM} with a
 * dynamic count) only requires a new constant: the selection UI, the
 * multi-signer screen and the sequential signing engine are all driven from
 * {@link #signerCount()} and never switch on concrete constants.</p>
 */
public enum SigningMode {

    /** One person signs the document (the classic single-signer flow). */
    SINGLE(1),

    /** Two people sign the same document, one after the other. */
    TWO_SIGNERS(2),

    /** Three people sign the same document, one after the other. */
    THREE_SIGNERS(3);

    private final int signerCount;

    SigningMode(int signerCount) {
        this.signerCount = signerCount;
    }

    /** Number of signers this mode requires. */
    public int signerCount() {
        return signerCount;
    }

    /** Whether this mode is the classic single-signer flow. */
    public boolean isSingle() {
        return this == SINGLE;
    }

    /** Whether more than one person signs the document. */
    public boolean isMultiSigner() {
        return signerCount > 1;
    }

    /** All modes the selection UI should offer, in display order. */
    public static List<SigningMode> selectableModes() {
        return List.of(SINGLE, TWO_SIGNERS, THREE_SIGNERS);
    }
}
