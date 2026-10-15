package com.example.signer.ui.kit;

import javafx.scene.paint.Color;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.ui.support.SignedPdfExport;
import com.example.signer.ui.workspace.PlacementViewer;
import com.example.signer.verification.SignatureProperties;
import com.example.signer.verification.SignatureStatus;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.verification.SignatureVerificationResult.SignatureDetail;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Read-only document review: the shared {@link PlacementViewer} (thumbnails,
 * page navigation, zoom, fit — one opened document, one render worker) plus
 * the list of signatures already in the document with their REAL
 * verification status (click one for its {@link SignaturePropertiesView}).
 *
 * <p>Call {@link #close()} when the window closes to release the PDF.
 */
public final class DocumentReviewPane {

    private final SignatureVerificationResult verification;
    private final PlacementViewer viewer;
    private final BorderPane root = new BorderPane();
    private final Path pdf;
    private final String documentName;

    public DocumentReviewPane(PdfRenderService renderService, Path pdf,
                              SignatureVerificationResult verification) {
        this(renderService, pdf, verification, null);
    }

    /** @param documentName used for the suggested file name when downloading */
    public DocumentReviewPane(PdfRenderService renderService, Path pdf,
                              SignatureVerificationResult verification, String documentName) {
        this.verification = verification;
        this.pdf = pdf;
        this.documentName = documentName != null ? documentName
                : pdf.getFileName() == null ? null : pdf.getFileName().toString();
        this.viewer = PlacementViewer.readOnly(renderService, pdf);
        root.setCenter(viewer.getRoot());
        root.setRight(signaturePanel());
    }

    public BorderPane getRoot() {
        return root;
    }

    /** Releases the document. */
    public void close() {
        viewer.close();
    }

    /** "Download signed PDF…" with a one-line result underneath. */
    private VBox downloadBox(boolean signed) {
        Button download = new Button(signed ? "Download signed PDF…" : "Download PDF…",
                new FontIcon(FontAwesomeSolid.DOWNLOAD));
        download.getStyleClass().add("btn-primary");
        download.setMaxWidth(Double.MAX_VALUE);
        Label result = new Label();
        result.getStyleClass().add("sf-text");
        result.setWrapText(true);
        result.setManaged(false);
        download.setOnAction(e -> {
            try {
                SignedPdfExport.saveCopy(root.getScene() == null ? null
                        : root.getScene().getWindow(), pdf, documentName)
                        .ifPresent(saved -> {
                            result.setText("Saved to " + saved);
                            result.setManaged(true);
                        });
            } catch (IOException ex) {
                result.setText("Could not save the file: " + ex.getMessage());
                result.setManaged(true);
            }
        });
        return new VBox(4, download, result);
    }

    /** Right column: every signature already in the document. */
    private VBox signaturePanel() {
        List<SignatureDetail> sigs = verification == null ? List.of()
                : verification.signatures();
        Label title = new Label("Signatures (" + sigs.size() + ")");
        title.getStyleClass().add("sf-detail-value");
        VBox panel = new VBox(10, downloadBox(!sigs.isEmpty()), title);
        panel.setPadding(new Insets(14));
        panel.setPrefWidth(250);
        panel.setMinWidth(200);
        if (sigs.isEmpty()) {
            panel.getChildren().add(SignFlowUi.text("Not signed yet. You will be the"
                    + " first signer."));
        }
        for (SignatureDetail sig : sigs) {
            SignatureStatus status = SignatureStatus.of(sig, verification);
            SignatureProperties p = sig.properties();
            String who = p != null ? SignatureProperties.commonName(p.certSubject())
                    : SignatureProperties.commonName(sig.signerSubject());
            FontIcon icon = new FontIcon(status.isValid()
                    ? FontAwesomeSolid.CHECK_CIRCLE : FontAwesomeSolid.EXCLAMATION_CIRCLE);
            icon.getStyleClass().add(status.isValid() ? "sf-ok-icon" : "sf-error-icon");
            icon.setIconSize(16);
            icon.setIconColor(Color.web(status.isValid()
                    ? "#16A34A" : "#DC2626"));
            Label name = new Label(who);
            name.getStyleClass().add("sf-detail-value");
            name.setWrapText(true);
            Label meta = new Label(status.title()
                    + (sig.signedAt() == null ? "" : " · " + SignFlowUi.dateTime(sig.signedAt())));
            meta.getStyleClass().add("sf-text");
            meta.setWrapText(true);
            Button details = new Button("View signature details");
            details.getStyleClass().add("btn-ghost");
            details.setOnAction(e -> SignaturePropertiesView.show(
                    root.getScene() == null ? null : root.getScene().getWindow(),
                    sig, verification, sigs.size()));
            VBox card = new VBox(4, new HBox(8, icon, name), meta, details);
            card.getStyleClass().add("sf-card");
            card.setPadding(new Insets(10));
            panel.getChildren().add(card);
        }
        return panel;
    }
}
