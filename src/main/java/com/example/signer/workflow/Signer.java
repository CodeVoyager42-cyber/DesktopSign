package com.example.signer.workflow;

import com.example.signer.token.SigningCertificate;

import java.util.Optional;

/**
 * One participant of a signing workflow.
 *
 * <p>A signer owns, in order: their position ({@code order}, 1-based), an
 * optional display name, the certificate they will sign with (resolved when
 * their token is detected), and their progress through the
 * {@link SigningStatus} lifecycle. The PIN never lives here — it exists only
 * as a transient {@code char[]} handed straight to {@code TokenService} and
 * wiped immediately after use.</p>
 */
public class Signer {

    private final int order;
    private String displayName;
    private SigningCertificate certificate;
    private SigningStatus status = SigningStatus.PENDING;

    public Signer(int order) {
        if (order < 1) {
            throw new IllegalArgumentException("signer order must be >= 1, got " + order);
        }
        this.order = order;
    }

    public Signer(int order, String displayName) {
        this(order);
        this.displayName = displayName;
    }

    /** 1-based position in the sequential signing order. */
    public int getOrder() {
        return order;
    }

    /** Human-readable name, or the generated fallback ("Signer 2") when unset. */
    public String getDisplayName() {
        return displayName == null || displayName.isBlank()
                ? "Signer " + order
                : displayName.trim();
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** Whether the user provided a custom name for this signer. */
    public boolean hasCustomName() {
        return displayName != null && !displayName.isBlank();
    }

    /** Certificate chosen for this signer, present once their token is ready. */
    public Optional<SigningCertificate> getCertificate() {
        return Optional.ofNullable(certificate);
    }

    public void setCertificate(SigningCertificate certificate) {
        this.certificate = certificate;
    }

    /** Alias of the signer's private key, or {@code null} before selection. */
    public String getKeyAlias() {
        return certificate == null ? null : certificate.getAlias();
    }

    public SigningStatus getStatus() {
        return status;
    }

    public void setStatus(SigningStatus status) {
        this.status = status;
    }

    /** Whether the signer is fully prepared for their signing turn. */
    public boolean isReadyToSign() {
        return status == SigningStatus.READY && certificate != null;
    }

    @Override
    public String toString() {
        return "Signer[" + order + ", " + getDisplayName() + ", " + status + "]";
    }
}
