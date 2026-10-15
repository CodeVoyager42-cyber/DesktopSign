package com.example.signer.ui.workspace;

import com.example.signer.exception.ErrorCode;
import com.example.signer.token.TokenException;
import com.example.signer.exception.SignerException;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.remote.SigningCardCheck;
import com.example.signer.signing.SignatureAppearance;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.signing.SignatureStampRenderer;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.ui.ErrorMessages;
import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.kit.SignFlowUi;
import com.example.signer.ui.kit.SignaturePropertiesView;
import com.example.signer.ui.support.Background;
import com.example.signer.ui.support.UiSupport;
import com.example.signer.verification.SignatureProperties;
import com.example.signer.verification.SignatureStatus;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.workflow.EffectiveWorkflowState;
import com.example.signer.workflow.WorkflowSignerStep;
import com.example.signer.workflow.WorkflowSigningService;

import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Continues a portable workflow PDF on this computer, in the same layout as
 * every other signing screen: the signers as step chips on top, the shared
 * {@link PlacementViewer} (earlier signatures' boxes shown) in the middle,
 * and the signer panel on the right — identity, actions, smart card and PIN,
 * appearance, and the signatures already in the document.
 *
 * <p>The workflow layer decides everything: {@link WorkflowSigningService#load}
 * reconciles the manifest with the real signature chain, and this screen
 * only renders {@link EffectiveWorkflowState}; it never decides on its own
 * whether signing is allowed. The save location is chosen BEFORE signing so
 * the service can write the result atomically.
 */
public final class WorkflowContinuationDialog {

    private static final Logger log = LoggerFactory.getLogger(WorkflowContinuationDialog.class);

    private static final float STAMP_W_PTS = 170f;
    private static final float STAMP_H_PTS = 55f;
    private static final List<String> REASONS = List.of("Document approval",
            "Contract approval", "Reviewed", "Accepted", "Authorized", "Other…");

    private final WorkflowSigningService workflowService;
    private final TokenService tokenService;
    private final PdfRenderService renderService;

    private Stage stage;
    private Path pdf;
    private boolean signedHere;
    private boolean busy;

    /** Loaded state of the current file (null until loaded). */
    private WorkflowSigningService.WorkflowDocument document;
    private EffectiveWorkflowState state;
    private WorkflowSignerStep currentStep;
    private SigningCardCheck cardCheck;

    // ---- layout ----------------------------------------------------------
    private final HBox stripHolder = new HBox();
    private final StackPane viewerHolder = new StackPane();
    private final VBox panelInner = new VBox(8);
    private final Label panelTitle = new Label("Workflow");
    private final Label panelPill = new Label();
    private ProgressIndicator spinner;
    private Label statusLabel;
    private PlacementViewer viewer;

    // ---- panel controls (rebuilt for each loaded revision) ----------------
    private Label placementLabel;
    private Label overlapLabel;
    private Button placeButton;
    private Button clearButton;
    private Button signButton;
    private Label whyDisabled;
    private VBox cardBox;
    private PasswordField pinField;
    private Button unlockButton;
    private ComboBox<String> reasonCombo;
    private TextField reasonOther;
    private TextField locationField;

    public WorkflowContinuationDialog(WorkflowSigningService workflowService,
                                      TokenService tokenService,
                                      PdfRenderService renderService) {
        this.workflowService = workflowService;
        this.tokenService = tokenService;
        this.renderService = renderService;
    }

    /** Shows the workspace for {@code pdfToOpen}; returns when it is closed. */
    public void showAndWait(Window owner, Path pdfToOpen) {
        this.pdf = pdfToOpen;
        this.signedHere = false;
        String documentName = pdfToOpen.getFileName().toString();

        stage = new Stage(StageStyle.DECORATED);
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Continue Workflow — " + documentName);
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        // ---- header: brand + document (same as the other workspaces) -------
        Label logo = new Label();
        logo.setGraphic(new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        logo.getStyleClass().add("app-logo");
        Label brand = new Label("SignFlow");
        brand.getStyleClass().add("app-name");
        Label docChip = new Label(documentName);
        docChip.getStyleClass().add("mws-doc-chip");
        docChip.setMaxWidth(240);
        docChip.setTextOverrun(OverrunStyle.ELLIPSIS);
        docChip.setTooltip(new Tooltip(pdfToOpen.toString()));
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, logo, brand, docChip, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        header.setPadding(new Insets(10, 16, 10, 16));

        stripHolder.setAlignment(Pos.CENTER);
        stripHolder.getStyleClass().add("mws-strip-row");

        // ---- right panel shell ---------------------------------------------
        panelTitle.getStyleClass().add("mws-panel-title");
        panelPill.getStyleClass().addAll("status-pill", "status-pill--pending");
        panelPill.setVisible(false);
        HBox panelHead = new HBox(8, panelTitle, panelPill);
        panelHead.setAlignment(Pos.CENTER_LEFT);
        panelInner.getStyleClass().add("mws-panel");
        ScrollPane panelScroll = new ScrollPane(panelInner);
        panelScroll.setFitToWidth(true);
        panelScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelScroll.getStyleClass().add("mws-panel-scroll");
        VBox.setVgrow(panelScroll, Priority.ALWAYS);
        VBox panel = new VBox(panelHead, panelScroll);
        panel.getStyleClass().add("mws-panel-root");
        panel.setPrefWidth(310);
        panel.setMinWidth(280);

        HBox.setHgrow(viewerHolder, Priority.ALWAYS);
        HBox center = new HBox(10, viewerHolder, panel);
        center.getStyleClass().add("mws-center");
        center.setPadding(new Insets(10, 12, 10, 12));

        // ---- status bar ------------------------------------------------------
        spinner = new ProgressIndicator();
        spinner.getStyleClass().add("mws-busy");
        spinner.setPrefSize(18, 18);
        statusLabel = new Label("Checking the document and its signatures…");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        HBox bottom = new HBox(10, spinner, statusLabel);
        bottom.getStyleClass().add("status-bar");
        bottom.setPadding(new Insets(8, 16, 8, 16));

        BorderPane root = new BorderPane();
        root.setTop(new VBox(header, stripHolder));
        root.setCenter(center);
        root.setBottom(bottom);
        root.getStyleClass().add("mws-root");

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root,
                Math.max(320, Math.min(1280, screen.getWidth() - 48)),
                Math.max(240, Math.min(820, screen.getHeight() - 48)));
        ThemeManager.instance().attach(scene);
        installKeys(scene);
        stage.setScene(scene);
        UiSupport.applyAppIcon(stage);

        // Never close in the middle of a signature (token unlocked, file
        // half-written): wait for the outcome instead.
        stage.setOnCloseRequest(e -> {
            if (busy) {
                e.consume();
            }
        });
        // Leaving ends this app's token session, like the other workspaces.
        stage.setOnHiding(e -> {
            if (viewer != null) {
                viewer.close();
            }
            try {
                tokenService.disconnect();
            } catch (Exception ex) {
                log.debug("Token cleanup on close failed: {}", ex.getMessage());
            }
        });

        load(null);
        stage.showAndWait();
    }

    /** @return true when a signature was created and saved in this session. */
    public boolean wasSignedHere() {
        return signedHere;
    }

    // ------------------------------------------------------------------ loading

    /** Reads {@link #pdf} in the background, then shows it. */
    private void load(String afterSignMessage) {
        setBusy(true);
        Task<WorkflowSigningService.WorkflowDocument> task = new Task<>() {
            @Override
            protected WorkflowSigningService.WorkflowDocument call() {
                return workflowService.load(pdf);
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            render(task.getValue(), afterSignMessage);
        });
        task.setOnFailed(e -> {
            setBusy(false);
            statusLabel.setText("The document could not be opened.");
            showAlert("This document could not be opened", UiSupport.failureMessage(task));
        });
        Background.run("wf-open", task);
    }

    private void render(WorkflowSigningService.WorkflowDocument doc, String afterSignMessage) {
        this.document = doc;
        this.state = doc.effective();
        this.currentStep = state == null ? null : state.currentStep();

        if (doc.parseFailure() != null) {
            showReadOnly(SignFlowUi.Tone.ERROR, "This document contains an invalid or"
                    + " unsupported signing workflow. " + doc.parseFailure().getMessage());
            statusLabel.setText("Signing is disabled for this document.");
            return;
        }
        if (doc.manifest() == null) {
            showReadOnly(SignFlowUi.Tone.INFO, "This PDF carries no signing workflow. To sign"
                    + " it, choose one, two or three signers on the home screen.");
            statusLabel.setText("No signing workflow in this document.");
            return;
        }
        if (state == null) {
            showReadOnly(SignFlowUi.Tone.WARN, "This workflow was created by a newer version"
                    + " of SignFlow. Its signatures remain valid and verifiable, but it"
                    + " can't be signed with this version.");
            statusLabel.setText("Signing is disabled for this document.");
            return;
        }

        stripHolder.getChildren().setAll(buildStrip(state));
        boolean completed = state.status() == EffectiveWorkflowState.WorkflowStatus.COMPLETED;
        boolean canSign = state.canSign() && currentStep != null;

        // Viewer: earlier signatures' boxes are shown so the new one avoids
        // them; a placement fixed in the workflow is used as-is.
        List<SignaturePlacement> earlier = new ArrayList<>();
        for (WorkflowSignerStep step : doc.manifest().stepsInOrder()) {
            if (step.placement() != null && step.signedAt() != null) {
                earlier.add(step.placement());
            }
        }
        showViewer(canSign
                ? new PlacementViewer(renderService, pdf, earlier, currentStep.placement())
                : PlacementViewer.readOnly(renderService, pdf));

        buildPanel(canSign, completed);
        if (afterSignMessage != null) {
            statusLabel.setText(afterSignMessage);
        } else if (completed) {
            statusLabel.setText("All signatures in this workflow are complete.");
        } else if (canSign) {
            statusLabel.setText(viewer.isLocked()
                    ? "The workflow sets where your signature goes. Review the document, then sign."
                    : "Review the document, click Place Signature and drag on the page.");
            checkCard();
        } else {
            statusLabel.setText("Signing is unavailable for this document.");
        }
    }

    private void showViewer(PlacementViewer next) {
        if (viewer != null) {
            viewer.close();
        }
        viewer = next;
        viewer.setInteractive(!busy);
        viewer.statusProperty().addListener((o, was, text) -> {
            if (text != null) {
                statusLabel.setText(text);
            }
        });
        viewer.placementProperty().addListener((o, was, now) -> refreshPanel());
        viewer.placingProperty().addListener((o, was, now) -> {
            if (placeButton != null) {
                placeButton.setText(now ? "Drag on the page…" : "Place Signature");
            }
        });
        viewerHolder.getChildren().setAll(viewer.getRoot());
    }

    /** A document that can't be signed here: show it, and say why. */
    private void showReadOnly(SignFlowUi.Tone tone, String message) {
        resetPanelControls();
        stripHolder.getChildren().clear();
        showViewer(PlacementViewer.readOnly(renderService, pdf));
        panelTitle.setText("Workflow");
        panelPill.setVisible(false);
        panelInner.getChildren().setAll(
                SignFlowUi.note(FontAwesomeSolid.INFO_CIRCLE, tone, message));
        if (document != null && document.verification() != null) {
            panelInner.getChildren().add(signaturesPane(document.verification()));
        }
    }

    // ------------------------------------------------------------------ strip

    private HBox buildStrip(EffectiveWorkflowState effective) {
        HBox strip = new HBox(6);
        strip.getStyleClass().add("mws-strip");
        strip.setAlignment(Pos.CENTER);
        List<EffectiveWorkflowState.StepView> steps = effective.steps();
        for (int i = 0; i < steps.size(); i++) {
            EffectiveWorkflowState.StepView step = steps.get(i);
            Label chip = new Label(step.order() + " · " + step.displayName());
            String style = step.fulfilled() && step.verified() ? "mws-chip--done"
                    : step.fulfilled() ? "mws-chip--failed"
                    : step.yourTurn() ? "mws-chip--active" : "mws-chip--pending";
            chip.getStyleClass().addAll("mws-chip", style);
            chip.setTooltip(new Tooltip(step.displayName() + " — "
                    + (step.fulfilled() && step.verified() ? "signed and verified"
                    : step.fulfilled() ? "signature problem"
                    : step.yourTurn() ? "signing now" : "waiting")
                    + " · certificate " + step.certificate().fingerprintShort()));
            strip.getChildren().add(chip);
            if (i < steps.size() - 1) {
                FontIcon arrow = new FontIcon(FontAwesomeSolid.CHEVRON_RIGHT);
                arrow.getStyleClass().add("mws-strip-arrow");
                strip.getChildren().add(arrow);
            }
        }
        return strip;
    }

    // ------------------------------------------------------------------ panel

    private void buildPanel(boolean canSign, boolean completed) {
        resetPanelControls();
        int total = state.steps().size();
        panelPill.getStyleClass().removeAll("status-pill--pending", "status-pill--active",
                "status-pill--error");
        panelPill.setVisible(true);
        if (completed) {
            panelTitle.setText("Workflow complete");
            panelPill.setText("Signed");
            panelPill.getStyleClass().add("status-pill--active");
        } else if (canSign) {
            panelTitle.setText("Signer " + currentStep.order() + " of " + total);
            panelPill.setText("Your turn");
            panelPill.getStyleClass().add("status-pill--active");
        } else {
            panelTitle.setText("Workflow");
            panelPill.setText("Can't sign");
            panelPill.getStyleClass().add("status-pill--error");
        }
        panelInner.getChildren().clear();

        // ---- identity: who signs now, with which certificate ----------------
        if (currentStep != null && !completed) {
            Label name = new Label(currentStep.displayName());
            name.getStyleClass().add("mws-identity-name");
            Label details = new Label("Signing turn " + currentStep.order() + " of " + total
                    + " · certificate " + currentStep.certificate().fingerprintShort());
            details.getStyleClass().add("section-hint");
            details.setWrapText(true);
            VBox identity = new VBox(4, name, details);
            identity.getStyleClass().add("mws-identity-box");
            panelInner.getChildren().add(identity);
        }

        for (String warning : state.warnings()) {
            panelInner.getChildren().add(SignFlowUi.note(FontAwesomeSolid.EXCLAMATION_TRIANGLE,
                    SignFlowUi.Tone.WARN, warning));
        }
        if (!canSign && !completed) {
            panelInner.getChildren().add(SignFlowUi.note(FontAwesomeSolid.BAN,
                    SignFlowUi.Tone.ERROR, "Signing is unavailable: "
                            + String.join(" ", state.signingBlockers())));
        }

        if (canSign) {
            placementLabel = new Label();
            placementLabel.getStyleClass().add("section-hint");
            placementLabel.setWrapText(true);
            overlapLabel = new Label("⚠ This placement overlaps an existing signature.");
            overlapLabel.getStyleClass().add("mws-overlap-warning");
            overlapLabel.setWrapText(true);
            panelInner.getChildren().addAll(placementLabel, overlapLabel,
                    pane("Actions", actionsBox()),
                    pane("Smart card & PIN", tokenBox()),
                    pane("Signature appearance", appearanceBox()));
        }
        if (document.verification() != null) {
            panelInner.getChildren().add(signaturesPane(document.verification()));
        }
        refreshPanel();
    }

    /** Forgets the previous revision's panel controls before a rebuild. */
    private void resetPanelControls() {
        placementLabel = null;
        overlapLabel = null;
        placeButton = null;
        clearButton = null;
        signButton = null;
        whyDisabled = null;
        cardBox = null;
        pinField = null;
        unlockButton = null;
        cardCheck = null;
    }

    private VBox actionsBox() {
        placeButton = new Button("Place Signature", new FontIcon(FontAwesomeSolid.PEN_FANCY));
        placeButton.getStyleClass().add("btn-secondary");
        placeButton.setMaxWidth(Double.MAX_VALUE);
        placeButton.setOnAction(e -> viewer.togglePlacing());
        clearButton = new Button("Clear", new FontIcon(FontAwesomeSolid.ERASER));
        clearButton.getStyleClass().add("btn-ghost");
        clearButton.setOnAction(e -> viewer.clearPlacement());
        signButton = new Button("Confirm & Sign…", new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        signButton.getStyleClass().add("btn-primary");
        signButton.setMaxWidth(Double.MAX_VALUE);
        signButton.setOnAction(e -> confirmAndSign());
        whyDisabled = new Label();
        whyDisabled.getStyleClass().add("section-hint");
        whyDisabled.setWrapText(true);
        if (viewer.isLocked()) {
            for (Button b : List.of(placeButton, clearButton)) {
                b.setVisible(false);
                b.setManaged(false);
            }
        }
        return new VBox(6, placeButton, clearButton, signButton, whyDisabled);
    }

    private VBox tokenBox() {
        cardBox = new VBox(6);
        pinField = new PasswordField();
        pinField.setPromptText("Smart card PIN");
        pinField.setOnAction(e -> unlock());
        unlockButton = new Button("Unlock", new FontIcon(FontAwesomeSolid.UNLOCK_ALT));
        unlockButton.getStyleClass().add("btn-secondary");
        unlockButton.setOnAction(e -> unlock());
        HBox pinRow = new HBox(8, pinField, unlockButton);
        HBox.setHgrow(pinField, Priority.ALWAYS);
        return new VBox(8, cardBox, pinRow);
    }

    private VBox appearanceBox() {
        reasonCombo = new ComboBox<>();
        reasonCombo.getItems().setAll(REASONS);
        reasonCombo.getSelectionModel().selectFirst();
        reasonCombo.setMaxWidth(Double.MAX_VALUE);
        reasonOther = new TextField();
        reasonOther.setPromptText("Custom reason");
        reasonOther.setVisible(false);
        reasonOther.setManaged(false);
        reasonCombo.setOnAction(e -> {
            boolean other = "Other…".equals(reasonCombo.getValue());
            reasonOther.setVisible(other);
            reasonOther.setManaged(other);
        });
        locationField = new TextField();
        locationField.setPromptText("Location (optional, e.g. Rabat, Morocco)");
        locationField.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, new Label("Reason"), reasonCombo, reasonOther,
                new Label("Location"), locationField);
    }

    private TitledPane signaturesPane(SignatureVerificationResult verification) {
        List<SignatureVerificationResult.SignatureDetail> sigs =
                verification.signatures() == null ? List.of() : verification.signatures();
        VBox box = new VBox(6);
        if (sigs.isEmpty()) {
            Label none = new Label("None yet — you are the first to sign.");
            none.getStyleClass().add("section-hint");
            box.getChildren().add(none);
        }
        for (SignatureVerificationResult.SignatureDetail sig : sigs) {
            SignatureStatus status = SignatureStatus.of(sig, verification);
            String who = SignatureProperties.commonName(
                    sig.properties() != null ? sig.properties().certSubject() : sig.signerSubject());
            FontIcon icon = new FontIcon(status.isValid()
                    ? FontAwesomeSolid.CHECK_CIRCLE : FontAwesomeSolid.EXCLAMATION_CIRCLE);
            icon.getStyleClass().add(status.isValid() ? "sf-ok-icon" : "sf-error-icon");
            Label name = new Label(who + " — " + status.title(), icon);
            name.setWrapText(true);
            Hyperlink details = new Hyperlink("Details");
            details.setOnAction(e -> SignaturePropertiesView.show(stage, sig, verification, sigs.size()));
            box.getChildren().add(new VBox(0, name, details));
        }
        return pane("Signatures in this document (" + sigs.size() + ")", box);
    }

    private static TitledPane pane(String title, VBox content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setExpanded(true);
        pane.setCollapsible(false);
        return pane;
    }

    /** Keeps labels and buttons in line with the placement, card and busy state. */
    private void refreshPanel() {
        if (signButton == null || viewer == null) {
            return;
        }
        SignaturePlacement p = viewer.getPlacement();
        placementLabel.setText(p == null
                ? "Signature: not placed yet — click Place Signature, then drag on the page."
                : "Signature: page " + (p.pageIndex() + 1) + " · " + Math.round(p.width())
                        + "×" + Math.round(p.height()) + " pt"
                        + (viewer.isLocked() ? " (position set by the workflow)" : ""));
        boolean overlap = viewer.overlapsExisting();
        overlapLabel.setVisible(overlap);
        overlapLabel.setManaged(overlap);
        boolean unlocked = isUnlocked();
        boolean cardOk = cardCheck != null && cardCheck.state().canSign();
        String why = p == null ? "Place your signature on the document first."
                : !cardOk ? "Insert the smart card holding this signer's certificate."
                : !unlocked ? "Unlock the smart card with its PIN."
                : "";
        whyDisabled.setText(why);
        signButton.setDisable(busy || !why.isEmpty());
        placeButton.setDisable(busy);
        clearButton.setDisable(busy || p == null);
        pinField.setDisable(busy || unlocked || !cardOk);
        unlockButton.setDisable(busy || unlocked || !cardOk);
        unlockButton.setText(unlocked ? "Unlocked" : "Unlock");
    }

    private boolean isUnlocked() {
        TokenState t = tokenService.getState();
        return t == TokenState.AUTHENTICATED || t == TokenState.SIGNATURE_COMPLETE;
    }

    // ------------------------------------------------------------------ card

    /** Looks for the card and checks it holds this signer's certificate (no PIN). */
    private void checkCard() {
        if (cardBox == null || currentStep == null) {
            return;
        }
        cardBox.getChildren().setAll(SignFlowUi.note(FontAwesomeSolid.SYNC,
                SignFlowUi.Tone.INFO, "Looking for your smart card…"));
        Task<SigningCardCheck> task = new Task<>() {
            @Override
            protected SigningCardCheck call() {
                return SigningCardCheck.run(tokenService, currentStep.certificate());
            }
        };
        task.setOnSucceeded(e -> showCard(task.getValue()));
        task.setOnFailed(e -> showCard(new SigningCardCheck(SigningCardCheck.State.ERROR, null)));
        Background.run("wf-card", task);
    }

    private void showCard(SigningCardCheck result) {
        cardCheck = result;
        SigningCardCheck.State s = result.state();
        SignFlowUi.Tone tone = s.canSign() ? SignFlowUi.Tone.OK
                : s == SigningCardCheck.State.NOT_DETECTED ? SignFlowUi.Tone.INFO
                : SignFlowUi.Tone.WARN;
        cardBox.getChildren().setAll(SignFlowUi.note(
                s.canSign() ? FontAwesomeSolid.ID_CARD : FontAwesomeSolid.CREDIT_CARD,
                tone, s.title() + ". " + s.explanation()));
        if (!s.canSign()) {
            Button again = SignFlowUi.secondary("Check again");
            again.setOnAction(e -> checkCard());
            cardBox.getChildren().add(again);
        }
        refreshPanel();
    }

    private void unlock() {
        char[] pin = UiSupport.pinChars(pinField);
        if (pin == null || pin.length == 0) {
            statusLabel.setText("Type the smart card PIN first.");
            return;
        }
        setBusy(true);
        statusLabel.setText("Unlocking the smart card…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                tokenService.detect(); // no-op when the card is already handled
                if (tokenService.getState() != TokenState.WAITING_FOR_PIN) {
                    tokenService.startAuthentication();
                }
                tokenService.authenticate(pin); // the PIN array is wiped by TokenService
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            pinField.clear();
            setBusy(false);
            statusLabel.setText("✓ Smart card unlocked — you can sign.");
        });
        task.setOnFailed(e -> {
            pinField.clear();
            setBusy(false);
            statusLabel.setText("The smart card could not be unlocked.");
            Throwable t = task.getException();
            boolean pinProblem = t instanceof TokenException te
                    && (te.getErrorCode() == ErrorCode.TOKEN_PIN_INCORRECT
                    || te.getErrorCode() == ErrorCode.TOKEN_PIN_LOCKED
                    || te.getErrorCode() == ErrorCode.TOKEN_LOGIN_FAILED);
            showAlert("The smart card could not be unlocked", pinProblem
                    ? "Check the PIN and try again. Careful: cards lock after a few wrong PINs."
                    : MultiSignerWorkspaceFlow.detectionProblem(t));
        });
        Background.run("wf-auth", task);
    }

    // ------------------------------------------------------------------ sign

    private void confirmAndSign() {
        SignaturePlacement placement = viewer.getPlacement();
        WorkflowSignerStep step = currentStep;
        if (placement == null || step == null || state == null || !state.canSign()) {
            return;
        }
        WorkflowSigningService.SignatureAppearanceHint hint = appearanceHint();
        SigningCertificate cert = cardCheck == null ? null : cardCheck.certificate();
        String tokenLabel;
        try {
            tokenLabel = tokenService.getTokenInfo().label();
        } catch (RuntimeException e) {
            tokenLabel = null;
        }
        boolean confirmed = SigningCheckpoint.confirm(stage, stampPreview(step, cert, hint, placement),
                List.of(new SigningCheckpoint.Row("Signer", step.displayName()),
                        new SigningCheckpoint.Row("Certificate", cert != null
                                ? SignatureProperties.commonName(cert.getSubject())
                                : step.certificate().fingerprintShort()),
                        new SigningCheckpoint.Row("Workflow", "Step " + step.order() + " of "
                                + state.steps().size()),
                        new SigningCheckpoint.Row("Page", String.valueOf(placement.pageIndex() + 1)),
                        new SigningCheckpoint.Row("Reason", hint.reason()),
                        new SigningCheckpoint.Row("Location", hint.location()),
                        new SigningCheckpoint.Row("Token", tokenLabel)));
        if (!confirmed) {
            return;
        }

        // Destination chosen BEFORE signing: the service writes atomically.
        FileChooser save = new FileChooser();
        save.setTitle("Save signed document");
        save.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        save.setInitialFileName(suggestedName(step.displayName()));
        File target = save.showSaveDialog(stage);
        if (target == null) {
            return;
        }
        Path output = target.toPath();

        setBusy(true);
        statusLabel.setText("Signing… keep your smart card connected.");
        Task<EffectiveWorkflowState> task = new Task<>() {
            @Override
            protected EffectiveWorkflowState call() {
                return workflowService.signNextStep(pdf, hint, output, step.order(), placement);
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            signedHere = true;
            pdf = output;
            EffectiveWorkflowState after = task.getValue();
            boolean completed = after.status() == EffectiveWorkflowState.WorkflowStatus.COMPLETED;
            String next = after.steps().stream().filter(EffectiveWorkflowState.StepView::yourTurn)
                    .map(EffectiveWorkflowState.StepView::displayName).findFirst()
                    .orElse("the next signer");
            String message = "✓ Signed and saved to " + output.getFileName() + ". "
                    + (completed ? "All signatures are complete."
                    : "Next: pass the document to " + next + ".");
            load(message);
        });
        task.setOnFailed(e -> {
            setBusy(false);
            statusLabel.setText("Signing failed — the document was not modified.");
            Throwable error = task.getException();
            log.warn("Workflow continuation signing failed", error);
            showAlert("Signing failed", error instanceof SignerException signer
                    ? signer.getMessage() : ErrorMessages.generic(error));
        });
        Background.run("wf-sign", task);
    }

    private WorkflowSigningService.SignatureAppearanceHint appearanceHint() {
        String reason = "Other…".equals(reasonCombo.getValue())
                ? reasonOther.getText() : reasonCombo.getValue();
        String location = locationField.getText();
        return new WorkflowSigningService.SignatureAppearanceHint(
                reason == null || reason.isBlank() ? null : reason.trim(),
                location == null || location.isBlank() ? null : location.trim());
    }

    /** The real stamp, drawn by the same renderer the signing step uses. */
    private javafx.scene.image.Image stampPreview(WorkflowSignerStep step, SigningCertificate cert,
                                                  WorkflowSigningService.SignatureAppearanceHint hint,
                                                  SignaturePlacement placement) {
        String cn = cert != null ? SignatureProperties.commonName(cert.getSubject())
                : step.displayName();
        SigningAlgorithm algorithm = cert != null && "EC".equals(cert.getPublicKeyAlgorithm())
                ? SigningAlgorithm.ECDSA_SHA256 : SigningAlgorithm.RSA_SHA256;
        SignatureAppearance appearance = new SignatureAppearance(hint.reason(), hint.location(),
                true, true, true, step.displayName());
        float w = placement == null ? STAMP_W_PTS : placement.width();
        float h = placement == null ? STAMP_H_PTS : placement.height();
        return SwingFXUtils.toFXImage(SignatureStampRenderer.render(w, h, cn, algorithm, appearance),
                null);
    }

    private String suggestedName(String signerName) {
        String base = pdf.getFileName().toString().replaceAll("(?i)\\.pdf$", "");
        String safe = signerName.replaceAll("[^A-Za-z0-9_-]", "_");
        return base + "_signed_by_" + safe + ".pdf";
    }

    // ------------------------------------------------------------------ helpers

    private void setBusy(boolean value) {
        busy = value;
        spinner.setVisible(value);
        if (viewer != null) {
            viewer.setInteractive(!value);
        }
        refreshPanel();
    }

    private void installKeys(Scene scene) {
        scene.setOnKeyPressed(e -> {
            if (viewer == null) {
                return;
            }
            if (e.isControlDown()) {
                switch (e.getCode()) {
                    case EQUALS, ADD -> { e.consume(); viewer.zoomIn(); }
                    case MINUS, SUBTRACT -> { e.consume(); viewer.zoomOut(); }
                    case DIGIT0 -> { e.consume(); viewer.resetZoom(); }
                    default -> { }
                }
                return;
            }
            switch (e.getCode()) {
                case PAGE_UP -> { e.consume(); viewer.goToPage(viewer.currentPage() - 1); }
                case PAGE_DOWN -> { e.consume(); viewer.goToPage(viewer.currentPage() + 1); }
                case ESCAPE -> {
                    if (viewer.placingProperty().get()) {
                        e.consume();
                        viewer.cancelPlacing();
                    }
                }
                default -> { }
            }
        });
    }

    private void showAlert(String header, String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(header);
        alert.setHeaderText(header);
        alert.setContentText(text);
        alert.initOwner(stage);
        alert.showAndWait();
    }
}
