package com.example.signer.verification;

import com.example.signer.token.SigningCertificate;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.util.Store;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.verification.SignatureVerificationResult.SignatureDetail;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Chain-aware CMS verifier for signed PDF documents.
 *
 * <p>Every signature in a PDF covers a specific byte range of one
 * <em>revision</em> of the file. Signing a document again appends an
 * incremental update: the new revision keeps every byte of the previous one,
 * so the earlier signature's range stays intact while no longer reaching the
 * end of the file. This is legitimate and must not be reported as tampering.
 *
 * <p>The verifier therefore treats the signatures as an ordered chain:
 *
 * <ol>
 *   <li>signatures are ordered by revision, i.e. by where their
 *       {@code /ByteRange} starts (ties broken by coverage length),</li>
 *   <li>every signature is cryptographically verified over its own covered
 *       bytes: the {@code /ByteRange} digest must match the CMS
 *       {@code message-digest} signed attribute and the signature value must
 *       verify against the signer certificate (RSA and ECDSA),</li>
 *   <li>each signature's coverage must be fully contained in the next
 *       signature's coverage — the chain may only ever append,</li>
 *   <li>only the <b>last</b> signature is required to reach the end of the
 *       file; bytes appended after it mean the document was modified after
 *       the most recent signature and the whole chain is rejected.</li>
 * </ol>
 *
 * <p>Unknown or weak (SHA-1) digest algorithms fail closed instead of
 * silently falling back to another algorithm.
 *
 * <p>The file is never loaded into memory: only the {@code /ByteRange}
 * segments of each signature are read from disk, in 64 KiB chunks, straight
 * into the digest. A configurable size guard rejects documents beyond a
 * sane limit before any parsing happens.
 */
@Service
public class DefaultPdfVerificationService implements PdfVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPdfVerificationService.class);

    private static final ASN1ObjectIdentifier OID_MESSAGE_DIGEST =
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.4");

    /** Chunk size for streaming the covered byte ranges into the digest. */
    private static final int HASH_CHUNK_BYTES = 64 * 1024;

    /** Default limit: 512 MiB. */
    private static final long DEFAULT_MAX_FILE_BYTES = 512L * 1024 * 1024;

    private final long maxFileBytes;

    public DefaultPdfVerificationService(
            @Value("${signature.verification.max-file-bytes:" + DEFAULT_MAX_FILE_BYTES + "}")
            long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public SignatureVerificationResult verifyPdf(Path signedPdf) throws Exception {
        long fileSize = Files.size(signedPdf);
        if (fileSize > maxFileBytes) {
            log.warn("Refusing to verify {} ({} bytes): the limit is {} bytes",
                    signedPdf, fileSize, maxFileBytes);
            return SignatureVerificationResult.failure(
                    "PDF is too large to verify (" + humanReadableBytes(fileSize)
                            + "); the limit is " + humanReadableBytes(maxFileBytes)
                            + " — use a dedicated PDF validation tool for this document",
                    null, "unknown");
        }

        List<PDSignature> signatures;
        Integer catalogDocMdp;
        try (PDDocument doc = Loader.loadPDF(signedPdf.toFile())) {
            signatures = doc.getSignatureDictionaries();
            catalogDocMdp = readCatalogDocMdp(doc);
        }
        if (signatures.isEmpty()) {
            log.warn("PDF has no signature dictionary");
            return SignatureVerificationResult.failure(
                    "PDF is not signed", null, "unknown");
        }

        // ── collect coverage windows and order them as a revision chain ──
        List<SignatureAtRevision> chain = new ArrayList<>(signatures.size());
        for (PDSignature pd : signatures) {
            SignatureAtRevision entry = coverageOf(pd);
            if (entry == null) {
                return SignatureVerificationResult.failure(
                        "Signature dictionary has no valid /ByteRange", null, "unknown");
            }
            chain.add(entry);
        }

        if (chain.get(0).start() != 0) {
            log.warn("First revision starts at byte {} instead of 0", chain.get(0).start());
            return SignatureVerificationResult.failure(
                    "Signature chain does not start at the beginning of the file",
                    null, "unknown");
        }

        // ── verify the cryptography of every signature over its own bytes.
        //    Every signature gets a detail entry, even when the chain is
        //    already broken, so the UI can show the status of the whole chain. ──
        int total = chain.size();
        List<SignatureDetail> details = new ArrayList<>(total);
        String lastSubject = "unknown";
        SigningAlgorithm lastAlgorithm = null;
        String firstCryptoFailure = null;
        for (int i = 0; i < total; i++) {
            SignatureAtRevision entry = chain.get(i);
            String label = total > 1 ? "signature " + (i + 1) + " of " + total : "signature";
            String[] signerFingerprint = new String[1];
            SignatureProperties[] properties = new SignatureProperties[1];
            SignatureVerificationResult result =
                    verifySignatureCrypto(signedPdf, fileSize, entry.signature(),
                            entry.range(), label, signerFingerprint, properties);
            Integer docMdp = extractDocMdp(entry.signature());
            if (docMdp == null && catalogDocMdp != null && i == 0) {
                // Catalog-level /Perms/DocMDP (no per-signature /Reference):
                // it certifies the original revision, i.e. the first signature.
                docMdp = catalogDocMdp;
            }
            if (result.valid()) {
                details.add(SignatureDetail.of(i, result.signerSubject(),
                        result.algorithm(), entry.signature().getSignDate(), true,
                        "Valid", signerFingerprint[0], docMdp, properties[0]));
                lastSubject = result.signerSubject();
                lastAlgorithm = result.algorithm();
            } else {
                details.add(SignatureDetail.of(i, result.signerSubject(),
                        result.algorithm(), entry.signature().getSignDate(), false,
                        result.message(), signerFingerprint[0], docMdp, properties[0]));
                if (firstCryptoFailure == null) {
                    firstCryptoFailure = result.message();
                }
                log.warn("{} failed: {}", label, result.message());
            }
        }
        if (firstCryptoFailure != null) {
            log.warn("Signature chain broken: {}", firstCryptoFailure);
            return SignatureVerificationResult.failure(
                    firstCryptoFailure, lastAlgorithm, lastSubject, details);
        }

        // ── the chain may only append: every revision must be contained in
        //    the coverage of the next one ──
        for (int i = 0; i < total - 1; i++) {
            SignatureAtRevision current = chain.get(i);
            SignatureAtRevision next = chain.get(i + 1);
            if (current.end() < next.start() || current.end() > next.end()) {
                log.warn("Signature {} (bytes {}-{}) is not contained in the next "
                                + "revision (bytes {}-{}): the document was modified "
                                + "between the two signatures",
                        i + 1, current.start(), current.end(), next.start(), next.end());
                return SignatureVerificationResult.failure(
                        "Document was modified between signatures (revision "
                                + (i + 1) + " is not contained in revision " + (i + 2) + ")",
                        lastAlgorithm, lastSubject, details);
            }
        }

        // ── only the most recent revision must reach the end of the file ──
        SignatureAtRevision last = chain.get(total - 1);
        if (last.end() != fileSize) {
            log.warn("Most recent signature covers bytes {}-{} but the file is {} bytes "
                            + "long: content was appended after signing",
                    last.start(), last.end(), fileSize);
            return SignatureVerificationResult.failure(
                    "Document was modified after the most recent signature",
                    lastAlgorithm, lastSubject, details);
        }

        log.info("Signature chain verified successfully: {} signature(s), "
                + "most recent by {}", total, lastSubject);
        return SignatureVerificationResult.success(lastAlgorithm, lastSubject, details);
    }

    /**
     * DocMDP access-permission value declared by a certification signature's
     * {@code /Reference} entry, or {@code null} for ordinary approval
     * signatures. Per the PDF specification: 1 = no changes, 2 = form fill
     * and further signatures, 3 = annotations, 4 = revocation info.
     */
    private static Integer extractDocMdp(PDSignature signature) {
        COSDictionary dict = signature.getCOSObject();
        COSArray refs = dict.getDictionaryObject(
                COSName.getPDFName("Reference")) instanceof
                COSArray array ? array : null;
        if (refs == null) {
            return null;
        }
        for (int i = 0; i < refs.size(); i++) {
            if (!(refs.getObject(i) instanceof COSDictionary ref)) {
                continue;
            }
            COSName transformMethod = ref.getCOSName(
                    COSName.getPDFName("TransformMethod"));
            if (!"DocMDP".equals(transformMethod == null ? null : transformMethod.getName())) {
                continue;
            }
            if (ref.getDictionaryObject(
                    COSName.getPDFName("TransformParams"))
                    instanceof COSDictionary params) {
                int p = params.getInt(COSName.getPDFName("P"), -1);
                if (p >= 1 && p <= 4) {
                    return p;
                }
            }
            return 2; // DocMDP transform without explicit P: default is P=2
        }
        return null;
    }

    /**
     * The catalog-level {@code /Perms/DocMDP} pointer, used by writers that
     * place the certification permissions outside the signature dictionary.
     * Returns {@code null} when absent.
     */
    private static Integer readCatalogDocMdp(PDDocument doc) {
        COSDictionary catalog =
                doc.getDocumentCatalog().getCOSObject();
        if (catalog.getDictionaryObject(COSName.getPDFName("Perms"))
                instanceof COSDictionary perms

                && perms.getDictionaryObject(COSName.getPDFName("DocMDP"))
                instanceof COSDictionary mdp) {
            int p = mdp.getInt(COSName.getPDFName("P"), -1);
            return p >= 1 && p <= 4 ? p : null;
        }
        return null;
    }

    /**
     * Extracts the covered byte window of a signature. Returns {@code null}
     * when the {@code /ByteRange} is missing or malformed.
     */
    private static SignatureAtRevision coverageOf(PDSignature pd) throws IOException {
        byte[] cms = pd.getContents();
        if (cms == null || cms.length == 0) {
            throw new IOException("Signature dictionary has no /Contents");
        }
        int[] range = pd.getByteRange();
        if (range == null || range.length != 4) {
            return null;
        }
        long r1s = range[0] & 0xFFFFFFFFL;
        long r1l = range[1] & 0xFFFFFFFFL;
        long r2s = range[2] & 0xFFFFFFFFL;
        long r2l = range[3] & 0xFFFFFFFFL;
        if (r1l < 0 || r2s < r1s || r2s + r2l <= r1s + r1l) {
            return null;
        }
        return new SignatureAtRevision(pd, range.clone(), r1s, r1l, r2s, r2l);
    }

    /**
     * Verifies the cryptography of one signature over the bytes it covers:
     * ByteRange digest vs CMS {@code message-digest}, and the signature value
     * over the DER-encoded signed attributes. Chain containment is checked
     * separately by {@link #verifyPdf(Path)}.
     *
     * <p>The covered bytes are streamed from disk in chunks; the file is
     * never materialised in memory.
     */
    private SignatureVerificationResult verifySignatureCrypto(
            Path pdfPath, long fileSize, PDSignature pd, int[] range, String label,
            String[] signerFingerprintOut)
            throws Exception {
        return verifySignatureCrypto(pdfPath, fileSize, pd, range, label,
                signerFingerprintOut, null);
    }

    private SignatureVerificationResult verifySignatureCrypto(
            Path pdfPath, long fileSize, PDSignature pd, int[] range, String label,
            String[] signerFingerprintOut, SignatureProperties[] propertiesOut)
            throws Exception {

        byte[] cms = pd.getContents();
        if (cms == null || cms.length == 0) {
            return SignatureVerificationResult.failure(
                    "Signature dictionary has no /Contents", null, "unknown");
        }
        if ((range[2] & 0xFFFFFFFFL) + (range[3] & 0xFFFFFFFFL) > fileSize) {
            log.warn("{}: /ByteRange extends beyond the end of the file", label);
            return SignatureVerificationResult.failure(
                    "Signature byte range exceeds the file size", null, "unknown");
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
        String subject = signerCert.getSubjectX500Principal().getName();
        if (signerFingerprintOut != null) {
            signerFingerprintOut[0] =
                    SigningCertificate.fingerprintOf(signerCert);
        }

        // Fail closed on unknown or weak digest algorithms.
        DigestAlgorithm digestAlgorithm = DigestAlgorithm.forOid(signer.getDigestAlgOID());
        if (digestAlgorithm == null) {
            log.warn("Unsupported digest algorithm OID {}", signer.getDigestAlgOID());
            return SignatureVerificationResult.failure(
                    "Unsupported digest algorithm: " + signer.getDigestAlgOID(),
                    null, subject);
        }
        SigningAlgorithm reportingAlgorithm = reportingAlgorithmFor(signerCert);
        if (propertiesOut != null) {
            propertiesOut[0] = propertiesOf(pd, signer, signerCert,
                    digestAlgorithm.jcaName(),
                    digestAlgorithm.signatureAlgorithmNameFor(signerCert));
        }

        byte[] rangeDigest = digestRanges(pdfPath, digestAlgorithm.jcaName(), range);

        byte[] embeddedDigest = embeddedMessageDigest(signer);
        if (embeddedDigest == null) {
            return SignatureVerificationResult.failure(
                    "CMS has no message-digest signed attribute",
                    reportingAlgorithm, subject);
        }
        if (!MessageDigest.isEqual(embeddedDigest, rangeDigest)) {
            log.warn("{}: covered content does not match the CMS message-digest", label);
            return SignatureVerificationResult.failure(
                    "Document content does not match the signature",
                    reportingAlgorithm, subject);
        }

        // Detached CMS: the signature covers the DER-encoded signed
        // attributes (which include the message-digest). Verify it
        // manually with JCA so BC does not try to hash missing content.
        byte[] signedAttributes = signer.getEncodedSignedAttributes();
        if (signedAttributes == null) {
            log.warn("CMS signer carries no signed attributes");
            return SignatureVerificationResult.failure(
                    "CMS signer carries no signed attributes",
                    reportingAlgorithm, subject);
        }
        boolean signatureValid = verifySignatureValue(
                signerCert, signer.getSignature(), signedAttributes,
                digestAlgorithm.signatureAlgorithmNameFor(signerCert));
        if (!signatureValid) {
            log.warn("{}: signature does not match the signer certificate", label);
            return SignatureVerificationResult.failure(
                    "Signature does not match the signer certificate",
                    reportingAlgorithm, subject);
        }

        log.debug("{}: cryptography verified ({})", label, reportingAlgorithm);
        return SignatureVerificationResult.success(reportingAlgorithm, subject);
    }

    /**
     * Computes the digest over the two {@code /ByteRange} segments without
     * loading the file: each segment is read from disk in 64 KiB chunks and
     * fed straight into the {@link MessageDigest}. Memory use is bounded by
     * the chunk size regardless of how large the document is.
     */
    private static byte[] digestRanges(Path pdfPath, String jcaDigestName, int[] range)
            throws IOException, NoSuchAlgorithmException {
        long r1s = range[0] & 0xFFFFFFFFL;
        long r1l = range[1] & 0xFFFFFFFFL;
        long r2s = range[2] & 0xFFFFFFFFL;
        long r2l = range[3] & 0xFFFFFFFFL;

        MessageDigest digest = MessageDigest.getInstance(jcaDigestName);
        try (FileChannel channel = FileChannel.open(pdfPath, StandardOpenOption.READ)) {
            hashSegment(channel, r1s, r1l, digest);
            hashSegment(channel, r2s, r2l, digest);
        }
        return digest.digest();
    }

    /** Feeds {@code length} bytes starting at {@code position} into the digest, in chunks. */
    private static void hashSegment(FileChannel channel, long position, long length,
                                    MessageDigest digest) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HASH_CHUNK_BYTES);
        long offset = position;
        long remaining = length;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(HASH_CHUNK_BYTES, remaining));
            int read = channel.read(buffer, offset);
            if (read <= 0) {
                // File shrank under us or the range was invalid: treat as
                // tampering rather than silently hashing short data.
                throw new IOException("File ended before the covered byte range did");
            }
            buffer.flip();
            digest.update(buffer);
            offset += read;
            remaining -= read;
        }
    }

    /**
     * Formats a byte count for human-facing messages (e.g. {@code 1.4 GB}).
     * Always uses a dot decimal separator so the message is stable across
     * user locales.
     */
    private static String humanReadableBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exponent = (int) (Math.log(bytes) / Math.log(1024));
        double value = bytes / Math.pow(1024, exponent);
        return String.format(Locale.ROOT, "%.1f %sB", value,
                new String[] {"", "K", "M", "G", "T"}[Math.min(exponent, 4)]);
    }

    /**
     * Verifies the raw CMS signature value with JCA against the signer
     * certificate's public key.
     */
    private static boolean verifySignatureValue(
            X509Certificate cert, byte[] signature, byte[] signedAttributes, String jcaAlgorithm) {
        try {
            Signature verifier = Signature.getInstance(jcaAlgorithm);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(signedAttributes);
            return verifier.verify(signature);
        } catch (Exception e) {
            log.warn("Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Closest application-level signing algorithm for reporting, derived from
     * the signer certificate's key type (RSA or ECDSA).
     */
    private static SigningAlgorithm reportingAlgorithmFor(X509Certificate signerCert) {
        return "EC".equals(signerCert.getPublicKey().getAlgorithm())
                ? SigningAlgorithm.ECDSA_SHA256
                : SigningAlgorithm.RSA_SHA256;
    }

    private static byte[] embeddedMessageDigest(SignerInformation signer) {
        Attribute attr = signer.getSignedAttributes()
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

    /**
     * Resolves the signer certificate from the CMS certificate store.
     *
     * <p>BouncyCastle's {@link Store} and
     * {@link SignerId} are non-generic by design (no
     * type-safe overload exists), so the selector match is inherently
     * unchecked; the suppression is scoped to this single lookup.</p>
     */
    @SuppressWarnings("unchecked")
    /**
     * Facts read from the signature dictionary and the signer certificate —
     * only what the file really contains (optional entries stay null).
     */
    static SignatureProperties propertiesOf(PDSignature pd, SignerInformation signer,
                                            X509Certificate cert, String digestName,
                                            String signatureAlgorithm) {
        Calendar signDate = pd.getSignDate();
        Boolean validAtSigning = null;
        if (signDate != null) {
            Date when = signDate.getTime();
            validAtSigning = !when.before(cert.getNotBefore())
                    && !when.after(cert.getNotAfter());
        }
        boolean timestamped = signer.getUnsignedAttributes() != null
                && signer.getUnsignedAttributes().get(
                        PKCSObjectIdentifiers
                                .id_aa_signatureTimeStampToken) != null;
        String subject = cert.getSubjectX500Principal().getName();
        String issuer = cert.getIssuerX500Principal().getName();
        return new SignatureProperties(
                blankToNull(pd.getSubFilter()),
                blankToNull(pd.getReason()),
                blankToNull(pd.getLocation()),
                blankToNull(pd.getContactInfo()),
                digestName,
                signatureAlgorithm,
                subject,
                issuer,
                cert.getSerialNumber().toString(16).toUpperCase(Locale.ROOT),
                cert.getNotBefore().toInstant(),
                cert.getNotAfter().toInstant(),
                cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()),
                validAtSigning,
                timestamped,
                SignatureProperties.TrustStatus.NOT_VERIFIED);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    /**
     * One signature positioned inside the file: its raw {@code /ByteRange}
     * (both segments, excluding the signature's own {@code /Contents} gap)
     * plus the revision window it covers, from the start of the first segment
     * to the end of the second.
     */
    private record SignatureAtRevision(
            PDSignature signature, int[] range, long start, long length, long r2s, long r2l) {

        /** Last byte covered by this revision, exclusive. */
        long end() {
            return r2s + r2l;
        }
    }

    /**
     * Digest algorithms supported for verification. SHA-1 is deliberately
     * absent: signatures relying on it are rejected as weak. Unknown OIDs map
     * to {@code null} so that verification fails closed.
     */
    private enum DigestAlgorithm {
        SHA256("2.16.840.1.101.3.4.2.1", "1.2.840.113549.2.9", "SHA-256", "SHA256"),
        SHA384("2.16.840.1.101.3.4.2.2", "1.2.840.113549.2.10", "SHA-384", "SHA384"),
        SHA512("2.16.840.1.101.3.4.2.3", "1.2.840.113549.2.11", "SHA-512", "SHA512");

        private final String nistOid;
        private final String rsadsiOid;
        private final String jcaName;
        private final String signaturePrefix;

        DigestAlgorithm(String nistOid, String rsadsiOid,
                        String jcaName, String signaturePrefix) {
            this.nistOid = nistOid;
            this.rsadsiOid = rsadsiOid;
            this.jcaName = jcaName;
            this.signaturePrefix = signaturePrefix;
        }

        static DigestAlgorithm forOid(String oid) {
            for (DigestAlgorithm candidate : values()) {
                if (candidate.nistOid.equals(oid) || candidate.rsadsiOid.equals(oid)) {
                    return candidate;
                }
            }
            return null;
        }

        String jcaName() {
            return jcaName;
        }

        /**
         * JCA signature algorithm for the verification operation, chosen from
         * the digest OID and the signer certificate's key type (RSA or ECDSA).
         */
        String signatureAlgorithmNameFor(X509Certificate signerCert) {
            String keyAlgorithm = signerCert.getPublicKey().getAlgorithm();
            String with = "RSA".equals(keyAlgorithm) ? "withRSA" : "withECDSA";
            return signaturePrefix + with;
        }
    }
}
