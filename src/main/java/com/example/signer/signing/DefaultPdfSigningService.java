package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

    private static final String OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11";

    private final SigningService signingService;
    private final TokenService tokenService;

    public DefaultPdfSigningService(SigningService signingService,
                                    TokenService tokenService) {
        this.signingService = signingService;
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

        X509Certificate signerCert = resolveCertificate(keyAlias);
        SigningResult[] captured = new SigningResult[1];

        try (PDDocument doc = Loader.loadPDF(inputPdf.toFile())) {

            SignatureInterface signer = content -> {
                try {
                    byte[] data = content.readAllBytes();

                    TokenContentSigner tokenSigner =
                            new TokenContentSigner(signingService, keyAlias, algorithm);

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
            signature.setName(keyAlias);
            signature.setSignDate(Calendar.getInstance());
            signature.setReason("Document signed with the PDF Digital Signer");

            SignatureOptions options = null;
            try {
                if (placement == null) {
                    doc.addSignature(signature, signer);
                } else {
                    options = new SignatureOptions();
                    attachVisibleSignature(doc, signature, options,
                            placement, signerCert, algorithm);
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
                                        SigningAlgorithm algorithm) throws IOException {
        int pageIndex = placement.pageIndex();
        if (pageIndex >= doc.getNumberOfPages()) {
            throw new IOException("Placement page " + (pageIndex + 1)
                    + " does not exist in the PDF (pages: " + doc.getNumberOfPages() + ")");
        }

        PDPage page = doc.getPage(pageIndex);
        PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        float pageHeight = box.getHeight();

        if (page.getRotation() != 0) {
            log.warn("Page {} is rotated by {} degrees; visible signature placement "
                    + "is only exact for non-rotated pages", pageIndex + 1,
                    page.getRotation());
        }

        // Designer coordinates: xAxis/yAxis give the TOP-LEFT corner, yAxis
        // measured downwards from the top of the page.
        float xAxis = placement.x();
        float yAxis = Math.max(0f, pageHeight - (placement.y() + placement.height()));

        String signerName = displayName(signerCert);
        BufferedImage stamp = buildSignatureStamp(
                placement.width(), placement.height(), signerName, algorithm);

        PDVisibleSignDesigner designer =
                new PDVisibleSignDesigner(doc, stamp, pageIndex + 1);
        designer.xAxis(xAxis)
                .yAxis(yAxis)
                .width(placement.width())
                .height(placement.height())
                .signatureFieldName("Signature1");

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
     * Draws the screen-printed signature stamp (white box, gold border, signer
     * details) with an aspect ratio matching the requested placement box. All
     * text is baked into the raster, so fonts cannot be missing on the target
     * viewer.
     */
    private BufferedImage buildSignatureStamp(float widthPts,
                                              float heightPts,
                                              String signerName,
                                              SigningAlgorithm algorithm) {
        int wPx = 360;
        int hPx = Math.max(72, Math.round(wPx * heightPts / widthPts));
        BufferedImage image = new BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, wPx, hPx);
            g.setColor(new Color(0xBF, 0x9B, 0x1B));
            int border = Math.max(2, wPx / 80);
            g.fillRect(0, 0, wPx, hPx);
            g.setColor(Color.WHITE);
            g.fillRect(border, border, wPx - 2 * border, hPx - 2 * border);

            String signedAt = DateTimeFormatter
                    .ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneOffset.UTC)
                    .format(java.time.Instant.now());
            String[] lines = {
                    "DIGITALLY SIGNED BY",
                    signerName,
                    signedAt + " UTC",
                    algorithm.getJcaName() + " via PKCS#11"
            };

            int fontSize = Math.max(6, Math.min(wPx / 26, hPx / (lines.length + 2)));
            g.setColor(new Color(0x2B, 0x2B, 0x2B));
            Font font;
            for (; fontSize > 6; fontSize--) {
                font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
                g.setFont(font);
                int maxWidth = Arrays.stream(lines)
                        .mapToInt(g.getFontMetrics()::stringWidth)
                        .max().orElse(0);
                if (maxWidth <= wPx - 4 * border) {
                    break;
                }
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, fontSize)));

            int lineHeight = g.getFontMetrics().getHeight();
            int y = lineHeight + (hPx - lines.length * lineHeight) / 2;
            for (String line : lines) {
                int textWidth = g.getFontMetrics().stringWidth(line);
                g.drawString(line, (wPx - textWidth) / 2, y);
                y += lineHeight;
            }
        } finally {
            g.dispose();
        }
        return image;
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
     * {@link ContentSigner} backed by a PKCS#11 token (or mock).
     */
    private static class TokenContentSigner implements ContentSigner {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final SigningService signingService;
        private final String keyAlias;
        private final SigningAlgorithm algorithm;
        private final AlgorithmIdentifier algorithmId;

        TokenContentSigner(SigningService signingService,
                           String keyAlias,
                           SigningAlgorithm algorithm) {
            this.signingService = signingService;
            this.keyAlias = keyAlias;
            this.algorithm = algorithm;
            this.algorithmId = new AlgorithmIdentifier(
                    new ASN1ObjectIdentifier(OID_SHA256_WITH_RSA),
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
            SigningResult result = signingService.sign(
                    new SigningRequest(signedAttrs, keyAlias, algorithm));
            return result.signature();
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algorithmId;
        }
    }
}
