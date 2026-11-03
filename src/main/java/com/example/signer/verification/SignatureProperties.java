package com.example.signer.verification;

import java.time.Instant;
import java.util.Locale;

/**
 * Facts read from ONE embedded PDF signature: its signature dictionary and
 * the signer certificate inside its CMS. Everything here comes from the
 * signed file itself — nothing is derived from the invitation, the
 * workflow or the server. Optional dictionary entries are {@code null} when
 * the signature does not carry them (the UI must then hide the row).
 *
 * <p>What the verifier does NOT check is stated explicitly: certificate
 * trust (chain to a trusted root) and revocation (CRL/OCSP) are
 * {@link TrustStatus#NOT_VERIFIED}; the signing time is the signer's own
 * clock unless a timestamp authority was used ({@link #timestamped()}).
 *
 * @param subFilter          the {@code /SubFilter}, e.g. {@code adbe.pkcs7.detached}
 * @param reason             {@code /Reason}, or {@code null}
 * @param location           {@code /Location}, or {@code null}
 * @param contactInfo        {@code /ContactInfo}, or {@code null}
 * @param digestAlgorithm    digest used by the CMS, e.g. {@code SHA-256}
 * @param signatureAlgorithm signature algorithm, e.g. {@code SHA256withRSA}
 * @param certSubject        signer certificate subject DN
 * @param certIssuer         signer certificate issuer DN
 * @param certSerialHex      signer certificate serial number (hex)
 * @param certNotBefore      certificate validity start
 * @param certNotAfter       certificate validity end
 * @param certSelfSigned     subject == issuer (no issuing authority)
 * @param certValidAtSigningTime whether the signing time falls inside the
 *                           certificate validity period; {@code null} when
 *                           the signature carries no signing time
 * @param timestamped        whether an RFC 3161 timestamp token is embedded
 * @param trust              certificate trust / revocation status
 */
public record SignatureProperties(
        String subFilter,
        String reason,
        String location,
        String contactInfo,
        String digestAlgorithm,
        String signatureAlgorithm,
        String certSubject,
        String certIssuer,
        String certSerialHex,
        Instant certNotBefore,
        Instant certNotAfter,
        boolean certSelfSigned,
        Boolean certValidAtSigningTime,
        boolean timestamped,
        TrustStatus trust) {

    /** Certificate trust as far as SignFlow checks it. */
    public enum TrustStatus {
        /** No chain building and no CRL/OCSP check is performed. */
        NOT_VERIFIED
    }

    /** Human label for the signature format, from the real {@code /SubFilter}. */
    public String formatLabel() {
        if (subFilter == null) {
            return "Unknown format";
        }
        return switch (subFilter) {
            case "ETSI.CAdES.detached" -> "PAdES (ETSI.CAdES.detached)";
            case "adbe.pkcs7.detached" -> "PDF digital signature, PKCS#7 detached"
                    + " (adbe.pkcs7.detached)";
            case "adbe.pkcs7.sha1" -> "PDF digital signature, PKCS#7 SHA-1"
                    + " (adbe.pkcs7.sha1)";
            case "ETSI.RFC3161" -> "Document timestamp (ETSI.RFC3161)";
            default -> subFilter;
        };
    }

    /** Whether the certificate's validity period has ended (at {@code now}). */
    public boolean certExpiredAt(Instant now) {
        return certNotAfter != null && now.isAfter(certNotAfter);
    }

    /** The common name (CN) of a DN, or the DN itself when it has none. */
    public static String commonName(String dn) {
        if (dn == null) {
            return null;
        }
        for (String part : dn.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String p = part.trim();
            if (p.toUpperCase(Locale.ROOT).startsWith("CN=")) {
                return p.substring(3);
            }
        }
        return dn;
    }

    /** The e-mail address embedded in a DN (E= / EMAILADDRESS=), or {@code null}. */
    public static String email(String dn) {
        if (dn == null) {
            return null;
        }
        for (String part : dn.split(",")) {
            String p = part.trim();
            String upper = p.toUpperCase(Locale.ROOT);
            if (upper.startsWith("E=")) {
                return p.substring(2);
            }
            if (upper.startsWith("EMAILADDRESS=")) {
                return p.substring("EMAILADDRESS=".length());
            }
            if (upper.startsWith("1.2.840.113549.1.9.1=")) {
                String v = p.substring("1.2.840.113549.1.9.1=".length());
                return v.startsWith("#") ? null : v; // DER-hex form: not decoded here
            }
        }
        return null;
    }
}
