package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSigProperties;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSignDesigner;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.SignerInfoGeneratorBuilder;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs a PDF by embedding a CMS {@code SignedData} structure inside a signed
 * copy of the document using Apache PDFBox incremental save.
 *
 * <p>Uses BouncyCastle's {@link CMSSignedDataGenerator} to produce a standards-
 * compliant CMS structure with proper signed attributes
 * ({@code content-type}, {@code message-digest}, {@code signing-time}).
 * A custom {@link TokenContentSigner} delegates the actual signing to the
 * PKCS#11 token (or mock).
 *
 * <p>The classic {@link SignatureInterface} callback is used: PDFBox passes
 * the ByteRange content to {@code sign()}, we build the CMS and return it.
 * PDFBox writes the CMS bytes directly into the signature placeholder during
 * incremental save, so no seeking is required on the output stream.
 */
@Service
public class DefaultPdfSigningService implements PdfSigningService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPdfSigningService.class);

    /** PKCS#1 rsassa-pkcs1-v1_5 with SHA-256 (RFC 8017). */
    private static final String OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11";

    /** ANSI X9.62 / SEC 2 ecdsa-with-SHA256 (RFC 5758). */
    private static final String OID_SHA256_WITH_ECDSA = "1.2.840.10045.4.3.2";

    /** SubFilter for the embedded CMS signature dictionaries. */
    public static final String SUBFILTER_ADBE_PKCS7_DETACHED =
            PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED.getName();

    private final TokenService tokenService;

    public DefaultPdfSigningService(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public SigningResult signPdf(Path inputPdf,
                                 String keyAlias,
                                 SigningAlgorithm algorithm,
                                 Path outputPdf) throws Exception {
        return signPdf(inputPdf, keyAlias, algorithm, outputPdf, null);
    }

    @Override
    public SigningResult signPdf(Path inputPdf,
                                 String keyAlias,
                                 SigningAlgorithm algorithm,
                                 Path outputPdf,
                                 SignaturePlacement placement) throws Exception {
        return signPdf(inputPdf, keyAlias, algorithm, outputPdf, placement, null);
    }

    @Override
    public SigningResult signPdf(Path inputPdf,
                                 String keyAlias,
                                 SigningAlgorithm algorithm,
                                 Path outputPdf,
                                 SignaturePlacement placement,
                                 SignatureAppearance appearance) throws Exception {
        return signPdf(inputPdf, keyAlias, algorithm, outputPdf, placement, appearance, null);
    }

    @Override
    public SigningResult signPdf(Path inputPdf,
                                 String keyAlias,
                                 SigningAlgorithm algorithm,
                                 Path outputPdf,
                                 SignaturePlacement placement,
                                 SignatureAppearance appearance,
                                 DocumentCustomizer documentCustomizer) throws Exception {

        X509Certificate signerCert = resolveCertificate(keyAlias);
        SigningResult[] captured = new SigningResult[1];

        try (PDDocument doc = Loader.loadPDF(inputPdf.toFile())) {

            // The customizer runs BEFORE addSignature: the manifest (or any
            // other document-side state) and the signature are serialised by
            // the SAME saveIncremental, so the new signature's /ByteRange
            // cryptographically covers the document state it ships with and
            // the chain still reaches EOF. Anything appended after the last
            // signature would invalidate the chain.
            if (documentCustomizer != null) {
                documentCustomizer.apply(doc);
            }

            SignatureInterface signer = content -> {
                try {
                    byte[] data = content.readAllBytes();

                    TokenContentSigner tokenSigner =
                            new TokenContentSigner(tokenService, keyAlias, algorithm);

                    BcDigestCalculatorProvider digestCalc = new BcDigestCalculatorProvider();

                    SignerInfoGenerator sigInfoGen =
                            new SignerInfoGeneratorBuilder(digestCalc)
                                    .build(tokenSigner,
                                            new JcaX509CertificateHolder(signerCert));

                    CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
                    generator.addSignerInfoGenerator(sigInfoGen);
                    generator.addCertificate(new JcaX509CertificateHolder(signerCert));

                    CMSProcessableByteArray contentBytes =
                            new CMSProcessableByteArray(data);

                    CMSSignedData signedData =
                            generator.generate(contentBytes, false);

                    SignerInformation signerInfo =
                            signedData.getSignerInfos().getSigners().iterator().next();

                    captured[0] = new SigningResult(
                            signerInfo.getSignature(),
                            algorithm,
                            signerCert.getSubjectX500Principal().getName());

                    return signedData.getEncoded();
                } catch (Exception ex) {
                    throw new IOException("CMS signature creation failed", ex);
                }
            };

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(signerCert.getSubjectX500Principal().getName());
            signature.setSignDate(Calendar.getInstance());
            if (appearance != null && appearance.reason() != null
                    && !appearance.reason().isBlank()) {
                signature.setReason(appearance.reason().trim());
            } else {
                signature.setReason("Document signed with the PDF Digital Signer");
            }
            if (appearance != null && appearance.location() != null
                    && !appearance.location().isBlank()) {
                signature.setLocation(appearance.location().trim());
            }

            SignatureOptions options = null;
            try {
                if (placement == null) {
                    doc.addSignature(signature, signer);
                } else {
                    options = new SignatureOptions();
                    attachVisibleSignature(doc, signature, options,
                            placement, signerCert, algorithm, appearance);
                    doc.addSignature(signature, signer, options);
                }

                try (OutputStream os = Files.newOutputStream(outputPdf)) {
                    doc.saveIncremental(os);
                }
            } finally {
                if (options != null) {
                    options.close();
                }
            }
        }

        log.info("Signed PDF written to {}", outputPdf);
        return captured[0];
    }

    /**
     * Configures a visible signature field at {@code placement} via PDFBox's
     * {@link PDVisibleSignDesigner}. Coordinates are converted from the
     * user-facing bottom-left origin to the designer's top-left origin.
     */
    private void attachVisibleSignature(PDDocument doc,
                                        PDSignature signature,
                                        SignatureOptions options,
                                        SignaturePlacement placement,
                                        X509Certificate signerCert,
                                        SigningAlgorithm algorithm,
                                        SignatureAppearance appearance) throws IOException {
        int pageIndex = placement.pageIndex();
        if (pageIndex >= doc.getNumberOfPages()) {
            throw new IOException("Placement page " + (pageIndex + 1)
                    + " does not exist in the PDF (pages: " + doc.getNumberOfPages() + ")");
        }

        PDPage page = doc.getPage(pageIndex);
        PDRectangle cropBox = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        PDRectangle mediaBox = page.getMediaBox();
        // The workspace measures the placement from the visible (crop) box,
        // while PDFBox's designer anchors its top-left coordinates to the
        // media box. Translate between the two so the stamp lands exactly
        // where the user drew it, even for scanned pages whose crop box is
        // offset inside the media box.
        float offsetX = cropBox.getLowerLeftX() - mediaBox.getLowerLeftX();
        float offsetY = cropBox.getLowerLeftY() - mediaBox.getLowerLeftY();

        if (page.getRotation() != 0) {
            log.warn("Page {} is rotated by {} degrees; visible signature placement "
                    + "is only exact for non-rotated pages", pageIndex + 1,
                    page.getRotation());
        }

        // Designer coordinates: xAxis/yAxis give the TOP-LEFT corner, yAxis
        // measured downwards from the top of the media box.
        float xAxis = offsetX + placement.x();
        float yAxis = Math.max(0f, mediaBox.getHeight() - offsetY
                - (placement.y() + placement.height()));

        String signerName = displayName(signerCert);
        BufferedImage stamp = buildSignatureStamp(
                placement.width(), placement.height(), signerName, algorithm,
                appearance);

        PDVisibleSignDesigner designer =
                new PDVisibleSignDesigner(doc, stamp, pageIndex + 1);
        designer.xAxis(xAxis)
                .yAxis(yAxis)
                .width(placement.width())
                .height(placement.height())
                .signatureFieldName(nextSignatureFieldName(doc));

        PDVisibleSigProperties visibleProps = new PDVisibleSigProperties()
                .signerName(signerName)
                .signerLocation("")
                .signatureReason("Signed with PKCS#11 token via PDF Digital Signer")
                .page(pageIndex + 1)
                .preferredSize(0)
                .setPdVisibleSignature(designer)
                .visualSignEnabled(true);
        visibleProps.buildSignature();

        signature.setName(signerName);
        options.setVisualSignature(visibleProps);
        options.setPage(pageIndex);
    }

    /**
     * Computes a signature field name that does not collide with any existing
     * form field, so a document can be signed repeatedly (multi-signature).
     */
    static String nextSignatureFieldName(PDDocument doc) {
        Set<String> used = new HashSet<>();
        PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
        if (acroForm != null) {
            acroForm.getFields().forEach(field -> used.add(field.getPartialName()));
        }
        int i = 1;
        while (used.contains("Signature" + i)) {
            i++;
        }
        return "Signature" + i;
    }

    /**
     * Draws the visible signature stamp — delegated to the shared
     * {@link SignatureStampRenderer} so the UI preview renders exactly the
     * stamp that is embedded.
     */
    private BufferedImage buildSignatureStamp(float widthPts,
                                              float heightPts,
                                              String signerName,
                                              SigningAlgorithm algorithm,
                                              SignatureAppearance appearance) {
        return SignatureStampRenderer.render(
                widthPts, heightPts, signerName, algorithm, appearance);
    }

    /**
     * Extracts the {@code CN} component of the certificate subject, or falls
     * back to the full subject string when it is missing.
     */
    private static String displayName(X509Certificate certificate) {
        String subject = certificate.getSubjectX500Principal().getName();
        Matcher matcher = Pattern.compile("(?:^|,)CN=([^,]+)").matcher(subject);
        return matcher.find() ? matcher.group(1) : subject;
    }

    private X509Certificate resolveCertificate(String keyAlias) {
        return tokenService.getCertificates().stream()
                .filter(c -> c.getAlias().equals(keyAlias))
                .findFirst()
                .map(SigningCertificate::getCertificate)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No certificate for alias: " + keyAlias));
    }

    /**
     * {@link ContentSigner} backed by a PKCS#11 token (or mock): feeds the
     * CMS signed attributes to {@link TokenService#sign} so the private key
     * operation happens inside the token.
     */
    private static class TokenContentSigner implements ContentSigner {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final TokenService tokenService;
        private final String keyAlias;
        private final SigningAlgorithm algorithm;
        private final AlgorithmIdentifier algorithmId;

        TokenContentSigner(TokenService tokenService,
                           String keyAlias,
                           SigningAlgorithm algorithm) {
            this.tokenService = tokenService;
            this.keyAlias = keyAlias;
            this.algorithm = algorithm;
            String oid = algorithm == SigningAlgorithm.ECDSA_SHA256
                    ? OID_SHA256_WITH_ECDSA
                    : OID_SHA256_WITH_RSA;
            this.algorithmId = new AlgorithmIdentifier(
                    new ASN1ObjectIdentifier(oid),
                    DERNull.INSTANCE);
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return buffer;
        }

        @Override
        public byte[] getSignature() {
            byte[] signedAttrs = buffer.toByteArray();
            buffer.reset();
            SigningResult result = tokenService.sign(
                    new SigningRequest(signedAttrs, keyAlias, algorithm));
            return result.signature();
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algorithmId;
        }
    }
}
