package com.example.signer.ui.workspace;

import com.example.signer.signing.SignatureAppearance;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.signing.SignatureStampRenderer;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.ui.support.UiSupport;
import com.example.signer.workflow.Signer;
import com.example.signer.workflow.SigningStatus;
import com.example.signer.workflow.SigningWorkflow;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;

/**
 * Right-hand panel of the "people on this computer" workspace for the signer
 * whose turn it is: identity, certificate, signature appearance and preview,
 * token PIN, and the placement / sign actions.
 *
 * <p>It shows state and forwards the user's actions; the workflow itself
 * (turns, placements, signing) stays in {@link MultiSignerWorkspaceFlow},
 * reached only through {@link Host}.
 */
final class SignerPanel {

    private static final Logger log = LoggerFactory.getLogger(SignerPanel.class);

    /** What the panel needs from the workspace that hosts it. */
    interface Host {
        SigningWorkflow workflow();

        /** The current signer's box, or null. */
        SignaturePlacement currentPlacement();

        /** True while a signature is being created (buttons stay disabled). */
        boolean isBusy();

        void togglePlacing();

        void clearPlacement();

        void confirmAndSign();

        void authenticateCurrent();

        /** The signer typed a different display name. */
        void signerNameChanged();

        void setStatus(String text);
    }

    private final Host host;
    private final TokenService tokenService;

    private final VBox root;
    private final Label title = new Label();
    private final Label statePill = new Label();
    private final ComboBox<String> certCombo = new ComboBox<>();
    private final Label certDetails = new Label(" ");
    private final ComboBox<String> reasonCombo = new ComboBox<>();
    private final TextField reasonOther = new TextField();
    private final TextField locationField = new TextField();
    private final CheckBox showDateBox = new CheckBox("Show signing date");
    private final CheckBox showCertBox = new CheckBox("Show certificate line");
    private final ImageView preview = new ImageView();
    private final Label placementLabel = new Label("Signature: not placed");
    private final Label overlapLabel = new Label();
    private final Label tokenLabel = new Label("Waiting for token…");
    private final PasswordField pinField = new PasswordField();
    private final Button unlockButton = new Button("Unlock Token",
            new FontIcon(FontAwesomeSolid.UNLOCK_ALT));
    private final Button placeBtn = new Button("Place Signature",
            new FontIcon(FontAwesomeSolid.PEN_FANCY));
    private final Button clearButton = new Button("Clear",
            new FontIcon(FontAwesomeSolid.ERASER));
    private final Button signButton = new Button("Confirm & Sign…",
            new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
    private final Label progressLabel = new Label(" ");
    private final TextField nameField = new TextField();
    private final Label identityName = new Label();
    private final Label identityDetails = new Label();
    private Signer boundSigner;

    SignerPanel(Host host, TokenService tokenService) {
        this.host = host;
        this.tokenService = tokenService;
        title.getStyleClass().add("mws-panel-title");
        statePill.getStyleClass().addAll("status-pill", "status-pill--pending");
        HBox head = new HBox(8, title, statePill);
        head.setAlignment(Pos.CENTER_LEFT);

        // ---- identity block: the right person must know it is their turn
        identityName.getStyleClass().add("mws-identity-name");
        identityDetails.getStyleClass().add("section-hint");
        identityDetails.setWrapText(true);
        nameField.setPromptText("Your name (optional — shown on the signature)");
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.textProperty().addListener((obs, was, now) -> {
            if (boundSigner != null) {
                boundSigner.setDisplayName(now);
                host.signerNameChanged();
                // The stamp preview must track the typed name live:
                // what the signer sees is what will be embedded.
                updatePreview();
            }
        });
        VBox identityBox = new VBox(4, identityName, identityDetails, nameField);
        identityBox.getStyleClass().add("mws-identity-box");

        certCombo.setMaxWidth(Double.MAX_VALUE);
        certCombo.setPromptText("Choose certificate");
        certDetails.getStyleClass().add("section-hint");
        certDetails.setWrapText(true);
        VBox certBox = new VBox(4, certCombo, certDetails);

        reasonCombo.getItems().setAll("Document approval", "Contract approval",
                "Reviewed", "Accepted", "Authorized", "Other…");
        reasonCombo.getSelectionModel().selectFirst();
        reasonCombo.setMaxWidth(Double.MAX_VALUE);
        reasonOther.setPromptText("Custom reason");
        reasonOther.setVisible(false);
        reasonOther.setManaged(false);
        reasonCombo.setOnAction(e -> {
            boolean other = "Other…".equals(reasonCombo.getValue());
            reasonOther.setVisible(other);
            reasonOther.setManaged(other);
            updatePreview();
        });
        locationField.setPromptText("Location (optional, e.g. Rabat, Morocco)");
        locationField.setMaxWidth(Double.MAX_VALUE);
        locationField.textProperty().addListener((o, w, n) -> updatePreview());
        showDateBox.setSelected(true);
        showDateBox.setOnAction(e -> updatePreview());
        showCertBox.setOnAction(e -> updatePreview());
        VBox appearanceBox = new VBox(4,
                new Label("Reason"), reasonCombo, reasonOther,
                new Label("Location"), locationField,
                showDateBox, showCertBox);

        preview.setFitWidth(180);
        preview.setPreserveRatio(true);
        preview.getStyleClass().add("mws-preview");
        VBox previewBox = new VBox(4, new Label("Signature preview"), preview);

        placementLabel.getStyleClass().add("section-hint");
        overlapLabel.getStyleClass().add("mws-overlap-warning");
        overlapLabel.setWrapText(true);
        overlapLabel.setVisible(false);
        overlapLabel.setManaged(false);

        tokenLabel.getStyleClass().add("section-hint");
        tokenLabel.setWrapText(true);
        pinField.setPromptText("Token PIN");
        pinField.setOnAction(e -> host.authenticateCurrent());
        unlockButton.getStyleClass().add("btn-secondary");
        unlockButton.setMaxWidth(Double.MAX_VALUE);
        unlockButton.setOnAction(e -> host.authenticateCurrent());
        HBox pinRow = new HBox(8, pinField, unlockButton);
        HBox.setHgrow(pinField, Priority.ALWAYS);
        VBox tokenBox = new VBox(4, tokenLabel, pinRow);

        placeBtn.getStyleClass().add("btn-secondary");
        placeBtn.setMaxWidth(Double.MAX_VALUE);
        placeBtn.setOnAction(e -> host.togglePlacing());
        clearButton.getStyleClass().add("btn-ghost");
        clearButton.setOnAction(e -> host.clearPlacement());
        signButton.getStyleClass().add("btn-primary");
        signButton.setMaxWidth(Double.MAX_VALUE);
        signButton.setOnAction(e -> host.confirmAndSign());
        progressLabel.getStyleClass().add("section-hint");
        progressLabel.setWrapText(true);
        VBox actionsBox = new VBox(6, placeBtn, clearButton, signButton,
                progressLabel);

        TitledPane certPane = new TitledPane("Certificate", certBox);
        TitledPane appearancePane = new TitledPane("Signature appearance",
                appearanceBox);
        TitledPane previewPane = new TitledPane("Preview", previewBox);
        TitledPane tokenPane = new TitledPane("Token & PIN", tokenBox);
        TitledPane actionsPane = new TitledPane("Actions", actionsBox);
        for (TitledPane pane : List.of(certPane, appearancePane, previewPane,
                tokenPane, actionsPane)) {
            pane.setExpanded(true);
            pane.setCollapsible(false);
        }
        VBox inner = new VBox(8, identityBox, placementLabel, overlapLabel,
                certPane, appearancePane, previewPane, tokenPane, actionsPane);
        inner.getStyleClass().add("mws-panel");
        ScrollPane scroll = new ScrollPane(inner);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("mws-panel-scroll");
        // The panel must scroll internally: without the vertical grow the
        // ScrollPane sizes to its content and overflows the window.
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root = new VBox(head, scroll);
        root.getStyleClass().add("mws-panel-root");
        root.setPrefWidth(310);
        root.setMinWidth(280);
    }

    VBox getRoot() {
        return root;
    }

    void refreshFor(Signer signer) {
        title.setText("Signer " + signer.getOrder());
        boundSigner = signer;
        refreshStatePill();
        nameField.setText(signer.hasCustomName() ? signer.getDisplayName() : "");
        identityName.setText(signer.getDisplayName());
        identityDetails.setText("Signing turn " + signer.getOrder() + " of "
                + host.workflow().signerCount()
                + " — your certificate appears here once your token is unlocked.");
        certCombo.getItems().clear();
        certDetails.setText(" ");
        tokenLabel.setText("Waiting for the signer's token…");
        // A new turn always begins locked: restore the PIN entry, which
        // the previous signer's AUTHENTICATED state had hidden. Without
        // this, signers 2 and 3 had nowhere to type their PIN.
        pinField.clear();
        pinField.setVisible(true);
        pinField.setManaged(true);
        pinField.setDisable(false);
        unlockButton.setDisable(false);
        placementLabel.setText("Signature: not placed");
        overlapLabel.setVisible(false);
        overlapLabel.setManaged(false);
        progressLabel.setText(" ");
        updatePreview();
    }

    /** Keeps the header pill in sync with the signer's live status. */
    void refreshStatePill() {
        Signer signer = boundSigner;
        if (signer == null) {
            return;
        }
        statePill.getStyleClass().removeAll("status-pill--pending",
                "status-pill--active", "status-pill--error");
        SigningStatus status = signer.getStatus();
        switch (status) {
            case AUTHENTICATING, SIGNING ->
                statePill.getStyleClass().add("status-pill--active");
            case FAILED, CANCELLED ->
                statePill.getStyleClass().add("status-pill--error");
            default -> statePill.getStyleClass().add("status-pill--pending");
        }
        statePill.setText(switch (status) {
            case READY -> "Ready";
            case AUTHENTICATING -> "Authenticating";
            case SIGNING -> "Signing";
            case SIGNED -> "Signed";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            default -> "Pending";
        });
    }

    /** One-shot success pulse so "just signed" reads at a glance. */
    void celebratePill() {
        MultiSignerWorkspaceFlow.pulseSuccess(statePill);
    }

    void refreshTokenUi() {
        TokenState state = tokenService.getState();
        switch (state) {
            case CERTIFICATE_AVAILABLE -> {
                tokenLabel.setText("\u2713 Token detected — choose the "
                        + "certificate and enter the PIN.");
                fillCerts();
                // Explicitly restore PIN entry: a previous signer's
                // unlocked state hid it, and each signer must type
                // their own PIN.
                pinField.setVisible(true);
                pinField.setManaged(true);
            }
            case WAITING_FOR_PIN -> {
                tokenLabel.setText("\u2713 Token detected — enter the PIN.");
                fillCerts();
                pinField.setVisible(true);
                pinField.setManaged(true);
            }
            case AUTHENTICATED, SIGNATURE_COMPLETE, SIGNATURE_FAILED -> {
                tokenLabel.setText("\u2713 Token unlocked — ready to sign.");
                fillCerts();
                pinField.setVisible(false);
                pinField.setManaged(false);
            }
            default -> tokenLabel.setText("\u26A0 Token not detected. Connect "
                    + "the signer's USB token.");
        }
        refreshButtons();
    }

    private void fillCerts() {
        List<SigningCertificate> certs;
        try {
            certs = tokenService.getCertificates();
        } catch (IllegalStateException e) {
            return;
        }
        List<String> labels = certs.stream()
                .map(c -> MultiSignerWorkspaceFlow.certificateName(c.getSubject()) + " ["
                        + c.getAlias() + "]")
                .toList();
        if (!labels.equals(certCombo.getItems())) {
            certCombo.getItems().setAll(labels);
            if (!labels.isEmpty()
                    && certCombo.getSelectionModel().getSelectedIndex() < 0) {
                certCombo.getSelectionModel().select(0);
            }
        }
        int index = certCombo.getSelectionModel().getSelectedIndex();
        if (index >= 0 && index < certs.size()) {
            SigningCertificate cert = certs.get(index);
            Instant until = cert.getValidUntil();
            String validity = until.isBefore(Instant.now())
                    ? "\u2715 expired "
                    : until.isBefore(Instant.now().plusSeconds(30L * 86_400))
                            ? "\u26A0 expires soon "
                            : "\u2713 valid ";
            certDetails.setText(validity + "— until "
                    + MultiSignerWorkspaceFlow.dateLabel(until) + "\n" + cert.getIssuer()
                    + "\n" + cert.getPublicKeyAlgorithm() + " "
                    + cert.getKeySize() + " bit");
        }
        updatePreview();
    }

    void refreshButtons() {
        Signer current = host.workflow().getCurrentSigner();
        TokenState state = tokenService.getState();
        boolean unlocked = state == TokenState.AUTHENTICATED
                || state == TokenState.SIGNATURE_COMPLETE;
        signButton.setDisable(!unlocked || host.currentPlacement() == null || host.isBusy());
        pinField.setDisable(unlocked);
        unlockButton.setDisable(unlocked || host.isBusy());
    }

    void setPlacement(SignaturePlacement placement) {
        placementLabel.setText("Signature: Page "
                + (placement.pageIndex() + 1) + " · "
                + Math.round(placement.width()) + "×"
                + Math.round(placement.height()) + " pt");
        refreshButtons();
        updatePreview();
    }

    /** Mirrors the viewer's "drawing a box" mode on the panel button. */
    void showPlacing(boolean placing) {
        placeBtn.setText(placing ? "Drag on the page…" : "Place Signature");
    }

    void setPlacementLabelCleared() {
        placementLabel.setText("Signature: not placed");
    }

    void showOverlapWarning(boolean show) {
        overlapLabel.setText(show
                ? "\u26A0 This placement overlaps an existing signature."
                : null);
        overlapLabel.setVisible(show);
        overlapLabel.setManaged(show);
    }

    void setProgress(String text) {
        progressLabel.setText(text);
        host.setStatus(text);
    }

    /** Refreshes the identity block once the signer's certificate is known. */
    void refreshIdentityAfterAuth() {
        Signer signer = boundSigner;
        if (signer == null) {
            return;
        }
        identityName.setText(signer.getDisplayName());
        String certName = signer.getCertificate()
                .map(cert -> MultiSignerWorkspaceFlow.certificateName(cert.getSubject()))
                .orElse(null);
        identityDetails.setText("Signing turn " + signer.getOrder() + " of "
                + host.workflow().signerCount()
                + (certName != null ? "  ·  certificate: " + certName : ""));
    }

    void clearPin() {
        pinField.clear();
    }

    char[] pinChars() {
        return UiSupport.pinChars(pinField);
    }

    String selectedAlias() {
        int index = certCombo.getSelectionModel().getSelectedIndex();
        List<SigningCertificate> certs;
        try {
            certs = tokenService.getCertificates();
        } catch (IllegalStateException e) {
            return null;
        }
        return index >= 0 && index < certs.size()
                ? certs.get(index).getAlias() : null;
    }

    SignatureAppearance appearance() {
        String reason = "Other…".equals(reasonCombo.getValue())
                ? reasonOther.getText()
                : reasonCombo.getValue();
        String location = locationField.getText();
        // The stamp's name line carries the signer's typed display name;
        // the engine re-applies it at signing time from the domain signer,
        // so preview and embedded stamp always agree.
        String displayName = boundSigner == null
                ? null : boundSigner.getDisplayName();
        return new SignatureAppearance(
                reason == null || reason.isBlank() ? null : reason,
                location == null || location.isBlank() ? null : location,
                true, showDateBox.isSelected(), showCertBox.isSelected(),
                displayName);
    }

    private void updatePreview() {
        Signer current = host.workflow().getCurrentSigner();
        if (current == null) {
            return;
        }
        try {
            BufferedImage stamp = SignatureStampRenderer.render(
                    MultiSignerWorkspaceFlow.DEFAULT_BOX_W_PTS, MultiSignerWorkspaceFlow.DEFAULT_BOX_H_PTS,
                    MultiSignerWorkspaceFlow.certificateName(current), MultiSignerWorkspaceFlow.algorithmFor(current),
                    appearance());
            preview.setImage(SwingFXUtils.toFXImage(stamp, null));
        } catch (RuntimeException e) {
            log.debug("Preview update skipped: {}", e.getMessage());
        }
    }
}
