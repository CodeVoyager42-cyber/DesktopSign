package com.example.signer.ui.kit;

import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.support.UiSupport;
import java.util.Locale;
import com.example.signer.verification.SignatureProperties;
import com.example.signer.verification.SignatureStatus;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.verification.SignatureVerificationResult.SignatureDetail;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.Instant;

/**
 * Acrobat-style "Signature Properties" for ONE embedded signature.
 *
 * <p>Every value comes from the signed PDF and its certificate (via the
 * verifier): rows whose data is absent are left out, and what SignFlow does
 * not check — certificate trust and revocation, trusted time — is said
 * explicitly instead of being shown as "OK". Invitation or workflow data is
 * never mixed in.
 */
public final class SignaturePropertiesView {

    private SignaturePropertiesView() {
    }

    /** Opens the properties window for signature {@code detail} of {@code chain}. */
    public static void show(Window owner, SignatureDetail detail,
                            SignatureVerificationResult chain, int signatureCount) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Signature properties");
        VBox content = build(detail, chain, signatureCount);
        Button close = SignFlowUi.secondary("Close");
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        content.getChildren().add(new HBox(close));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sf-scroll");
        Scene scene = new Scene(scroll, 560, 680);
        ThemeManager.instance().attach(scene);
        stage.setScene(scene);
        UiSupport.applyAppIcon(stage);
        stage.show();
    }

    /** The panel itself (also embeddable). */
    public static VBox build(SignatureDetail detail, SignatureVerificationResult chain,
                             int signatureCount) {
        SignatureStatus status = SignatureStatus.of(detail, chain);
        SignatureProperties p = detail == null ? null : detail.properties();
        SignFlowUi.Tone tone = status.isValid() ? SignFlowUi.Tone.OK
                : status == SignatureStatus.CERTIFICATE_NOT_VALID_AT_SIGNING
                ? SignFlowUi.Tone.WARN : SignFlowUi.Tone.ERROR;

        VBox box = new VBox(14);
        box.setPadding(new Insets(20));
        box.getStyleClass().add("sf-window");

        Label heading = SignFlowUi.title(signatureCount > 1
                ? "Signature " + (detail.index() + 1) + " of " + signatureCount
                : "Signature");
        HBox statusRow = SignFlowUi.note(status.isValid()
                        ? FontAwesomeSolid.CHECK_CIRCLE : FontAwesomeSolid.EXCLAMATION_TRIANGLE,
                tone, status.title() + " — " + status.explanation()
                        + (status.isValid() || detail == null || detail.valid()
                        ? "" : " (" + detail.message() + ")")
                        + (status == SignatureStatus.MODIFIED_AFTER_SIGNING && chain != null
                        ? " " + chain.message() + "." : ""));
        box.getChildren().addAll(heading, statusRow);

        if (p == null) {
            box.getChildren().add(SignFlowUi.text(detail == null || detail.signerSubject() == null
                    ? "No certificate information is available for this signature."
                    : "Signer: " + detail.signerSubject()));
            return box;
        }

        Instant now = Instant.now();
        box.getChildren().addAll(
                section("Signer"),
                SignFlowUi.details(
                        "Name", SignatureProperties.commonName(p.certSubject()),
                        "Email", SignatureProperties.email(p.certSubject()),
                        "Certificate subject", p.certSubject()),
                section("Signing information"),
                SignFlowUi.details(
                        "Signing time", detail.signedAt() == null ? null
                                : SignFlowUi.dateTime(detail.signedAt())
                                + (p.timestamped() ? "" : "  (signer's computer clock)"),
                        "Reason", p.reason(),
                        "Location", p.location(),
                        "Contact", p.contactInfo()),
                section("Signature details"),
                SignFlowUi.details(
                        "Format", p.formatLabel(),
                        "Digest algorithm", p.digestAlgorithm(),
                        "Signature algorithm", p.signatureAlgorithm(),
                        "Timestamp", p.timestamped()
                                ? "Embedded timestamp token (not validated by SignFlow)"
                                : "None — no timestamp authority was used",
                        "Certification", detail.docMdp() == null ? null
                                : "Certification signature (DocMDP level " + detail.docMdp() + ")"),
                section("Certificate"),
                SignFlowUi.details(
                        "Issued to", SignatureProperties.commonName(p.certSubject()),
                        "Issued by", p.certSelfSigned()
                                ? SignatureProperties.commonName(p.certIssuer()) + " (self-signed)"
                                : SignatureProperties.commonName(p.certIssuer()),
                        "Serial number", p.certSerialHex(),
                        "Valid from", SignFlowUi.dateTime(p.certNotBefore()),
                        "Valid until", SignFlowUi.dateTime(p.certNotAfter())
                                + (p.certExpiredAt(now) ? "  (expired)" : ""),
                        "At signing time", p.certValidAtSigningTime() == null ? null
                                : p.certValidAtSigningTime() ? "Certificate was valid"
                                : "Certificate was NOT valid",
                        "SHA-256 fingerprint", detail.signerCertFingerprint()),
                section("Validation"),
                check(detail.valid(), "Signature matches the document and the certificate"),
                check(chain == null || chain.valid(), chain == null || chain.valid()
                        ? "Document integrity verified (nothing changed except later signatures)"
                        : "Document integrity: " + chain.message()),
                SignFlowUi.note(FontAwesomeSolid.INFO_CIRCLE, SignFlowUi.Tone.WARN,
                        "Certificate trust and revocation are not verified by SignFlow:"
                                + " it does not check that the certificate was issued by a"
                                + " trusted authority or that it has not been revoked."));
        return box;
    }

    private static Label section(String text) {
        Label label = new Label(text.toUpperCase(Locale.ROOT));
        label.getStyleClass().add("sf-detail-key");
        label.setStyle("-fx-font-weight: 700;");
        VBox.setMargin(label, new Insets(6, 0, 0, 0));
        return label;
    }

    private static HBox check(boolean ok, String text) {
        return SignFlowUi.note(ok ? FontAwesomeSolid.CHECK : FontAwesomeSolid.TIMES,
                ok ? SignFlowUi.Tone.OK : SignFlowUi.Tone.ERROR, text);
    }
}
