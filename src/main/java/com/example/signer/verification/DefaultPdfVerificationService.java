package com.example.signer.verification;

import com.example.signer.token.SigningAlgorithm;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * CMS-level verifier for signed PDF documents.
 *
 * <p>Loads the signed file with PDFBox, extracts the first signature
 * dictionary, recomputes the digest of the {@code /ByteRange} covered bytes and
 * compares it with the {@code message-digest} signed attribute carried in the
 * embedded CMS {@code SignedData}. It also cryptographically verifies the
 * RSA signature against the signer certificate inside the CMS.
 */
@Service
public class DefaultPdfVerificationService implements PdfVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPdfVerificationService.class);

    private static final ASN1ObjectIdentifier OID_MESSAGE_DIGEST =
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.4");

    @Override
    public SignatureVerificationResult verifyPdf(Path signedPdf) throws Exception {
        byte[] pdf = Files.readAllBytes(signedPdf);

        try (PDDocument doc = Loader.loadPDF(signedPdf.toFile())) {
            if (doc.getSignatureDictionaries().isEmpty()) {
                log.warn("PDF has no signature dictionary");
                return SignatureVerificationResult.failure(
                        "PDF is not signed", null, "unknown");
            }
            PDSignature pd = doc.getSignatureDictionaries().get(0);

            byte[] cms = pd.getContents();
            if (cms == null || cms.length == 0) {
                return SignatureVerificationResult.failure(
                        "Signature dictionary has no /Contents", null, "unknown");
            }

            int[] range = pd.getByteRange();
            if (range == null || range.length != 4) {
                return SignatureVerificationResult.failure(
                        "Signature dictionary has no valid /ByteRange", null, "unknown");
            }

            CMSSignedData signedData = new CMSSignedData(cms);
            SignerInformationStore store = signedData.getSignerInfos();
            if (store.size() != 1) {
                return SignatureVerificationResult.failure(
                        "Expected exactly one signer, found " + store.size(),
                        null, "unknown");
            }
            SignerInformation signer = store.getSigners().iterator().next();

            X509Certificate signerCert = extractSignerCertificate(signedData, signer);
            if (signerCert == null) {
                return SignatureVerificationResult.failure(
                        "No signer certificate found in the CMS", null, "unknown");
            }

            String digestAlgorithm = digestAlgorithmFor(signer.getDigestAlgOID());
            byte[] rangeDigest = MessageDigest.getInstance(digestAlgorithm)
                    .digest(concatRanges(pdf, range));

            byte[] embeddedDigest = embeddedMessageDigest(signer);
            if (embeddedDigest == null) {
                return SignatureVerificationResult.failure(
                        "CMS has no message-digest signed attribute",
                        SigningAlgorithm.RSA_SHA256, signerCert.getSubjectX500Principal().getName());
            }
            if (!MessageDigest.isEqual(embeddedDigest, rangeDigest)) {
                log.warn("ByteRange content does not match the CMS message-digest");
                return SignatureVerificationResult.failure(
                        "Document content does not match the signature",
                        SigningAlgorithm.RSA_SHA256, signerCert.getSubjectX500Principal().getName());
            }

            // Detached CMS: the signature covers the DER-encoded signed
            // attributes (which include the message-digest). Verify it
            // manually with JCA so BC does not try to hash missing content.
            byte[] signedAttributes = signer.getEncodedSignedAttributes();
            if (signedAttributes == null) {
                log.warn("CMS signer carries no signed attributes");
                return SignatureVerificationResult.failure(
                        "CMS signer carries no signed attributes",
                        SigningAlgorithm.RSA_SHA256, signerCert.getSubjectX500Principal().getName());
            }
            boolean signatureValid = verifyRsaSignature(
                    signerCert, signer.getSignature(), signedAttributes,
                    signatureAlgorithmFor(signer.getDigestAlgOID()));
            if (!signatureValid) {
                log.warn("RSA signature does not match the signer certificate");
                return SignatureVerificationResult.failure(
                        "RSA signature does not match the signer certificate",
                        SigningAlgorithm.RSA_SHA256, signerCert.getSubjectX500Principal().getName());
            }

            log.info("Signature verified successfully for {}", signedPdf);
            return SignatureVerificationResult.success(
                    SigningAlgorithm.RSA_SHA256,
                    signerCert.getSubjectX500Principal().getName());
        }
    }

    private static byte[] concatRanges(byte[] pdf, int[] range) {
        int r1s = range[0];
        int r1l = range[1];
        int r2s = range[2];
        int r2l = range[3];
        if (r1s < 0 || r1l < 0 || r2s < 0 || r2l < 0
                || r1s + r1l > pdf.length || r2s + r2l > pdf.length) {
            throw new IllegalArgumentException("ByteRange exceeds PDF size");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(pdf, r1s, r1l);
        out.write(pdf, r2s, r2l);
        return out.toByteArray();
    }

    private static byte[] embeddedMessageDigest(SignerInformation signer) {
        org.bouncycastle.asn1.cms.Attribute attr = signer.getSignedAttributes()
                .get(OID_MESSAGE_DIGEST);
        if (attr == null) {
            return null;
        }
        try {
            return ((ASN1OctetString) attr.getAttrValues().getObjectAt(0)
                    .toASN1Primitive()).getOctets();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static X509Certificate extractSignerCertificate(
            CMSSignedData signedData, SignerInformation signer) throws Exception {
        Collection<X509CertificateHolder> matches =
                signedData.getCertificates().getMatches(signer.getSID());
        if (matches.isEmpty()) {
            return null;
        }
        return new JcaX509CertificateConverter()
                .setProvider(new BouncyCastleProvider())
                .getCertificate(matches.iterator().next());
    }

    private static String digestAlgorithmFor(String digestAlgOID) {
        return switch (digestAlgOID) {
            case "2.16.840.1.101.3.4.2.1", "1.2.840.113549.2.9" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2", "1.2.840.113549.2.10" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3", "1.2.840.113549.2.11" -> "SHA-512";
            case "1.2.840.113549.2.5", "1.3.14.3.2.26" -> "SHA-1";
            default -> "SHA-256";
        };
    }

    private static String signatureAlgorithmFor(String digestAlgOID) {
        return switch (digestAlgorithmFor(digestAlgOID)) {
            case "SHA-256" -> "SHA256withRSA";
            case "SHA-384" -> "SHA384withRSA";
            case "SHA-512" -> "SHA512withRSA";
            case "SHA-1" -> "SHA1withRSA";
            default -> "SHA256withRSA";
        };
    }

    private static boolean verifyRsaSignature(
            X509Certificate cert, byte[] signature, byte[] signedAttributes, String jcaAlgorithm) {
        try {
            Signature verifier = Signature.getInstance(jcaAlgorithm);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(signedAttributes);
            return verifier.verify(signature);
        } catch (Exception e) {
            log.warn("RSA signature verification error: {}", e.getMessage());
            return false;
        }
    }
}