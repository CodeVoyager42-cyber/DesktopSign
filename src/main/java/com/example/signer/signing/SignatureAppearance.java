package com.example.signer.signing;

/**
 * What the visible signature stamp shows (§11/§12). Only information that is
 * actually available is rendered — signer name and certificate come from the
 * real certificate, the date from the signing operation itself, reason and
 * location only when the user supplied them. Nothing here can fabricate
 * facts; it only selects which true facts to display.
 *
 * @param reason              optional signing reason shown in the stamp and
 *                            stored as the signature's {@code /Reason}
 * @param location            optional signing location shown in the stamp and
 *                            stored as {@code /Location}
 * @param showSignerName      include the signer's name line
 * @param showDate            include the signing date/time
 * @param showCertificateLine include a certificate identification line
 * @param signerDisplayName   optional display name shown in the stamp's name
 *                            line (what the signer typed in the UI). When
 *                            null/blank the certificate CN is used instead;
 *                            the certificate line always shows the real CN.
 */
public record SignatureAppearance(
        String reason,
        String location,
        boolean showSignerName,
        boolean showDate,
        boolean showCertificateLine,
        String signerDisplayName) {

    /** The application's standard appearance: name, date and certificate line. */
    public static SignatureAppearance standard() {
        return new SignatureAppearance(null, null, true, true, true, null);
    }

    /** Compact appearance: name and date only. */
    public static SignatureAppearance compact() {
        return new SignatureAppearance(null, null, true, true, false, null);
    }

    /** Copy of this appearance with the stamp's display name set. */
    public SignatureAppearance withSignerName(String displayName) {
        String trimmed = displayName == null ? null : displayName.trim();
        return new SignatureAppearance(reason, location, showSignerName,
                showDate, showCertificateLine,
                trimmed == null || trimmed.isBlank() ? null : trimmed);
    }
}
