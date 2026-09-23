package com.example.signer.ui.workspace;

import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.support.UiSupport;
import com.example.signer.workflow.EffectiveWorkflowState;
import com.example.signer.workflow.WorkflowManifest;
import com.example.signer.workflow.WorkflowSigningService;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Cross-PC workflow continuation workspace (Phase 8).
 *
 * <p>Opens a PDF that carries a portable workflow manifest, reconciles it
 * against the real signature chain through
 * {@link WorkflowSigningService#load}, shows the signer timeline with the
 * effective state, and — when the domain layer allows it — drives one
 * signing turn end to end: pre-sign confirmation, token unlock, signing,
 * post-sign verification and save.</p>
 *
 * <p>All authorization decisions come from the workflow domain layer; this
 * UI only renders {@link EffectiveWorkflowState} and never decides on its
 * own whether signing is allowed.</p>
 */
public final class WorkflowContinuationDialog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkflowContinuationDialog.class);

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final WorkflowSigningService workflowService;
    private final TokenService tokenService;

    private Stage dialog;
    private VBox content;
    private ProgressIndicator spinner;
    private Label statusLabel;
    private PasswordField pinField;
    private Button unlockButton;
    private Button signButton;
    private Label signReason;
    private VBox timelineBox;
    private Path pdf;
    private EffectiveWorkflowState state;
    private boolean signedHere;

    public WorkflowContinuationDialog(WorkflowSigningService workflowService,
                                      TokenService tokenService) {
        this.workflowService = workflowService;
        this.tokenService = tokenService;
    }

    /** Shows the continuation workspace for {@code pdfToOpen}. */
    public void showAndWait(Window owner, Path pdfToOpen) {
        this.pdf = pdfToOpen;
        this.signedHere = false;

        dialog = new Stage(StageStyle.DECORATED);
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Continue Signing Workflow");
        dialog.setMinWidth(680);
        dialog.setMinHeight(560);
        UiSupport.applyAppIcon(dialog);

        // ---- header -------------------------------------------------------
        Label logo = new Label();
        logo.setGraphic(new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        logo.getStyleClass().add("app-logo");
        Label brand = new Label("SignFlow");
        brand.getStyleClass().add("app-name");
        Label tagline = new Label("Workflow document");
        tagline.getStyleClass().add("app-tagline");
        HBox brandRow = new HBox(10, logo, new VBox(1, brand, tagline));
        brandRow.setAlignment(Pos.CENTER_LEFT);

        Button themeToggle = com.example.signer.ui.ThemeManager.instance()
                .createToggleButton();
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(brandRow, spacer, themeToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");

        Label title = new Label(pdfToOpen.getFileName().toString());
        title.getStyleClass().add("workflow-title");
        title.setWrapText(true);

        spinner = new ProgressIndicator();
        spinner.getStyleClass().add("mws-busy");
        spinner.setPrefSize(18, 18);
        spinner.setVisible(true);

        statusLabel = new Label("Verifying existing signatures…");
        statusLabel.getStyleClass().add("doc-meta");
        statusLabel.setWrapText(true);

        HBox statusRow = new HBox(8, spinner, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        timelineBox = new VBox(8);
        timelineBox.getStyleClass().add("wf-timeline");

        content = new VBox(14, header, title, statusRow, timelineBox);
        content.getStyleClass().add("wf-root");
        content.setPadding(new Insets(18));
        content.setSpacing(14);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("content-scroll");

        // ---- footer: Back to the workflow selection screen -----------------
        Button backButton = new Button("Back", new FontIcon(FontAwesomeSolid.ARROW_LEFT));
        backButton.getStyleClass().add("btn-ghost");
        backButton.setCancelButton(true); // Esc = Back
        backButton.setTooltip(new Tooltip(
                "Return to the workflow selection screen (Esc)"));
        backButton.setOnAction(e -> dialog.close());

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, backButton, footerSpacer);
        footer.getStyleClass().add("workflow-footer");
        footer.setPadding(new Insets(10, 18, 12, 18));

        VBox root = new VBox(scroll, footer);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Scene scene = new Scene(root, 720, 600);
        ThemeManager.instance().attach(scene);
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);

        // Leaving this workspace ends this app's token session — the same
        // cleanup the multi-signer workspace performs when it is closed.
        // disconnect() is a safe no-op when no token is connected.
        dialog.setOnHiding(e -> {
            try {
                tokenService.disconnect();
            } catch (Exception ex) {
                log.debug("Token cleanup on close failed: {}", ex.getMessage());
            }
        });

        loadInBackground();
        dialog.showAndWait();
    }

    /** @return true when a signature was created and saved in this session. */
    public boolean wasSignedHere() {
        return signedHere;
    }

    /* ------------------------------------------------------------------
     * loading / reconciliation (background)
     * ------------------------------------------------------------------ */

    private void loadInBackground() {
        Task<WorkflowSigningService.WorkflowDocument> task = new Task<>() {
            @Override
            protected WorkflowSigningService.WorkflowDocument call() {
                return workflowService.load(pdf);
            }
        };
        task.setOnSucceeded(e -> render(task.getValue()));
        task.setOnFailed(e -> {
            spinner.setVisible(false);
            statusLabel.setText("The document could not be opened.");
            showFatal("This document could not be opened",
                    UiSupport.failureMessage(task));
        });
        new Thread(task, "wf-open").start();
    }

    private void render(WorkflowSigningService.WorkflowDocument doc) {
        spinner.setVisible(false);

        if (doc.parseFailure() != null) {
            statusLabel.setText("Workflow document detected — problem found");
            renderFatalWorkflow(doc.parseFailure().getMessage());
            return;
        }
        if (doc.manifest() == null) {
            statusLabel.setText("");
            renderNoWorkflow();
            return;
        }

        EffectiveWorkflowState effective = doc.effective();
        if (effective == null) {
            statusLabel.setText("");
            renderFatalWorkflow(
                    "This workflow was created by a newer version of the application."
                            + " The document's signatures remain valid and verifiable.");
            return;
        }
        this.state = effective;
        statusLabel.setText(describeStatus(effective, doc.manifest()));

        renderTimeline(effective);
        renderActionArea(effective);
    }

    /* ------------------------------------------------------------------
     * timeline (§18)
     * ------------------------------------------------------------------ */

    private void renderTimeline(EffectiveWorkflowState effective) {
        timelineBox.getChildren().clear();

        Label heading = new Label("SIGNING WORKFLOW");
        heading.getStyleClass().add("section-title");
        timelineBox.getChildren().add(heading);

        for (EffectiveWorkflowState.StepView step : effective.steps()) {
            timelineBox.getChildren().add(stepRow(step));
        }

        if (state != null && !state.warnings().isEmpty()) {
            VBox warnings = new VBox(4);
            warnings.getStyleClass().addAll("card", "wf-banner--warn");
            for (String warning : state.warnings()) {
                warnings.getChildren().add(new Label(warning));
            }
            timelineBox.getChildren().add(warnings);
        }
        if (!effective.signingBlockers().isEmpty()
                && effective.status() != EffectiveWorkflowState.WorkflowStatus.COMPLETED) {
            VBox blockers = new VBox(4);
            blockers.getStyleClass().addAll("card", "wf-banner--danger");
            for (String blocker : effective.signingBlockers()) {
                blockers.getChildren().add(new Label(blocker));
            }
            timelineBox.getChildren().add(blockers);
        }
    }

    private HBox stepRow(EffectiveWorkflowState.StepView step) {
        FontIcon icon;
        String statusWord;
        String style;
        if (step.fulfilled() && step.verified()) {
            icon = new FontIcon(FontAwesomeSolid.CHECK_CIRCLE);
            statusWord = "Verified";
            style = "wf-step--done";
        } else if (step.yourTurn()) {
            icon = new FontIcon(FontAwesomeSolid.ARROW_RIGHT);
            statusWord = "Your turn";
            style = "wf-step--current";
        } else {
            icon = new FontIcon(FontAwesomeSolid.CIRCLE);
            statusWord = "Waiting";
            style = "wf-step--waiting";
        }

        Label name = new Label(step.displayName());
        name.getStyleClass().add("chain-signer");

        Label status = new Label(statusWord);
        status.getStyleClass().add("chain-badge");

        Label detail = new Label(stepDetail(step));
        detail.getStyleClass().add("chain-time");
        detail.setWrapText(true);
        HBox.setHgrow(detail, Priority.ALWAYS);

        HBox row = new HBox(10, icon, name, status, detail);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("chain-row", style);
        return row;
    }

    private String stepDetail(EffectiveWorkflowState.StepView step) {
        StringBuilder detail = new StringBuilder();
        if (step.signedAt() != null) {
            detail.append("Signed ")
                    .append(TIME_FORMAT.format(step.signedAt()))
                    .append(" (time claimed in the signature)");
        } else {
            detail.append("Certificate ")
                    .append(step.certificate().fingerprintShort());
        }
        return detail.toString();
    }

    /* ------------------------------------------------------------------
     * action area (§37): button state + reason, token panel (§14/§15)
     * ------------------------------------------------------------------ */

    private void renderActionArea(EffectiveWorkflowState effective) {
        signReason = new Label();
        signReason.getStyleClass().add("doc-meta");
        signReason.setWrapText(true);

        boolean completed =
                effective.status() == EffectiveWorkflowState.WorkflowStatus.COMPLETED;

        if (completed) {
            signButton = new Button("Workflow Completed",
                    new FontIcon(FontAwesomeSolid.CHECK_CIRCLE));
            signButton.setDisable(true);
            signReason.setText("All required signatures have been completed.");
        } else if (!effective.canSign()) {
            signButton = new Button("Sign Document");
            signButton.setDisable(true);
            signReason.setText("Signing is unavailable: "
                    + String.join(" ", effective.signingBlockers()));
        } else {
            signButton = new Button("Sign Document",
                    new FontIcon(FontAwesomeSolid.FEATHER_ALT));
            signButton.getStyleClass().add("btn-primary");
            signButton.setDefaultButton(true);
            signButton.setOnAction(e -> confirmAndSign());
            signReason.setText(stepSummary(effective));
        }
        signButton.getStyleClass().add("btn-primary");

        HBox actionRow = new HBox(12, signButton, signReason);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Label tokenHeading = new Label("TOKEN & SIGNATURE");
        tokenHeading.getStyleClass().add("section-title");
        VBox actionCard = new VBox(10, tokenHeading, actionRow);
        actionCard.getStyleClass().add("card");
        refreshTokenPanel(actionCard, effective);
        timelineBox.getChildren().add(actionCard);
    }

    private String stepSummary(EffectiveWorkflowState effective) {
        WorkflowSignerStepView view = currentView(effective);
        if (view == null) {
            return "";
        }
        return "Step " + view.order() + " of " + effective.steps().size()
                + " — " + view.name() + " · certificate "
                + view.fingerprint();
    }

    private WorkflowSignerStepView currentView(EffectiveWorkflowState effective) {
        Integer order = effective.currentStepOrder();
        if (order == null) {
            return null;
        }
        for (EffectiveWorkflowState.StepView step : effective.steps()) {
            if (step.order() == order) {
                return new WorkflowSignerStepView(step.order(), step.displayName(),
                        step.certificate().fingerprintShort());
            }
        }
        return null;
    }

    private void refreshTokenPanel(VBox actionCard, EffectiveWorkflowState effective) {
        TokenState tokenState = tokenService.getState();
        boolean tokenReady = tokenState == TokenState.AUTHENTICATED
                || tokenState == TokenState.SIGNATURE_COMPLETE;
        if (tokenReady || pinField != null) {
            return; // already satisfied / panel already built
        }
        Label tokenLabel = new Label("Token: not unlocked — enter the PIN to continue");
        tokenLabel.getStyleClass().add("doc-meta");

        pinField = new PasswordField();
        pinField.setPromptText("Token PIN");
        pinField.getStyleClass().add("login-field");
        pinField.setPrefColumnCount(12);
        pinField.setOnAction(e -> unlockToken(effective));

        unlockButton = new Button("Unlock Token", new FontIcon(FontAwesomeSolid.KEY));
        unlockButton.getStyleClass().addAll("btn-secondary", "btn-sm");
        unlockButton.setOnAction(e -> unlockToken(effective));

        HBox pinRow = new HBox(8, pinField, unlockButton);
        pinRow.setAlignment(Pos.CENTER_LEFT);
        actionCard.getChildren().add(new VBox(6, tokenLabel, pinRow));
    }

    private void unlockToken(EffectiveWorkflowState effective) {
        if (pinField == null) {
            return;
        }
        char[] pin = UiSupport.pinChars(pinField);
        if (pin == null || pin.length == 0) {
            statusLabel.setText("Type the token PIN first.");
            return;
        }
        unlockButton.setDisable(true);
        pinField.setDisable(true);
        statusLabel.setText("Authenticating to the token…");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                tokenService.detect();
                tokenService.startAuthentication();
                tokenService.authenticate(pin); // PIN wiped by TokenService
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.setText("Token authenticated.");
            pinField.clear();
            pinField.setDisable(true);
            unlockButton.setVisible(false);
            signButton.setDisable(false);
            signReason.setText(stepSummary(state));
        });
        task.setOnFailed(e -> {
            unlockButton.setDisable(false);
            pinField.setDisable(false);
            statusLabel.setText("Token authentication failed.");
            showAlert(Alert.AlertType.ERROR, "Token error",
                    "The token could not be unlocked. Check the PIN and try again.");
        });
        new Thread(task, "wf-auth").start();
    }

    /* ------------------------------------------------------------------
     * signing (§38 pre-sign confirmation, §39 post-sign, §40 export)
     * ------------------------------------------------------------------ */

    private void confirmAndSign() {
        EffectiveWorkflowState current = state;
        if (current == null || !current.canSign()) {
            return;
        }
        WorkflowSignerStepView view = currentView(current);
        if (view == null) {
            return;
        }
        String confirmText = "Document: " + pdf.getFileName() + "\n"
                + "Signer: " + view.name() + "\n"
                + "Certificate (SHA-256): " + view.fingerprint() + "\n"
                + "Workflow: step " + view.order() + " of "
                + current.steps().size() + "\n"
                + "Previous signatures: "
                + current.steps().stream().filter(
                        EffectiveWorkflowState.StepView::fulfilled).count() + " verified";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Ready to sign");
        confirm.setHeaderText("Ready to sign");
        confirm.setContentText(confirmText);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }

        // §40: destination chosen BEFORE signing; the service writes atomically.
        FileChooser save = new FileChooser();
        save.setTitle("Save signed document");
        save.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        save.setInitialFileName(suggestedName(view.name()));
        java.io.File target = save.showSaveDialog(dialog);
        if (target == null) {
            return;
        }

        signButton.setDisable(true);
        spinner.setVisible(true);
        statusLabel.setText("Signing document…");

        Path outputPath = target.toPath();
        Task<EffectiveWorkflowState> task = new Task<>() {
            @Override
            protected EffectiveWorkflowState call() {
                return workflowService.signNextStep(pdf, null, outputPath);
            }
        };
        task.setOnSucceeded(e -> {
            signedHere = true;
            pdf = outputPath;
            renderAfterSign(task.getValue(), view, outputPath);
        });
        task.setOnFailed(e -> {
            spinner.setVisible(false);
            signButton.setDisable(false);
            statusLabel.setText("Signing failed — the document was not modified.");
            Throwable error = task.getException();
            showAlert(Alert.AlertType.ERROR, "Signing failed",
                    error instanceof com.example.signer.exception.SignerException signer
                            ? signer.getMessage()
                            : com.example.signer.ui.ErrorMessages.generic(error));
        });
        new Thread(task, "wf-sign").start();
    }

    private void renderAfterSign(EffectiveWorkflowState after,
                                 WorkflowSignerStepView signedAs,
                                 Path savedTo) {
        this.state = after;
        spinner.setVisible(false);
        statusLabel.setText("Saved: " + savedTo.getFileName());

        renderTimeline(after);

        boolean completed =
                after.status() == EffectiveWorkflowState.WorkflowStatus.COMPLETED;
        VBox success = new VBox(6);
        success.getStyleClass().addAll("card", "wf-banner--success");
        success.getChildren().addAll(
                new Label("✓ Your signature is valid (" + signedAs.name() + ")."),
                new Label("✓ All previous signatures remain valid."),
                new Label("✓ Workflow updated and saved."));
        if (completed) {
            success.getChildren().add(
                    new Label("All required signatures have been completed."));
        } else {
            String next = after.steps().stream()
                    .filter(s -> s.yourTurn())
                    .map(EffectiveWorkflowState.StepView::displayName)
                    .findFirst().orElse("the next signer");
            success.getChildren().add(new Label("→ Next: pass the document to " + next + "."));
        }
        timelineBox.getChildren().add(success);

        signButton.setText(completed ? "Workflow Completed" : "Sign Document");
        signButton.setDisable(true);
        signReason.setText(completed
                ? "All required signatures have been completed."
                : "This turn is done — pass the document to the next signer.");
    }

    private String suggestedName(String signerName) {
        String base = pdf.getFileName().toString().replaceAll("(?i)\\.pdf$", "");
        String safe = signerName.replaceAll("[^A-Za-z0-9_-]", "_");
        return base + "_signed_by_" + safe + ".pdf";
    }

    /* ------------------------------------------------------------------
     * non-workflow / broken states (§19)
     * ------------------------------------------------------------------ */

    private void renderNoWorkflow() {
        Label icon = new Label();
        icon.setGraphic(new FontIcon(FontAwesomeSolid.FILE_PDF));
        icon.getStyleClass().add("empty-state-icon");
        Label title = new Label("No signing workflow in this document");
        title.getStyleClass().add("empty-state-title");
        Label hint = new Label(
                "This PDF carries no portable workflow. Start a new workflow from"
                        + " the workflow selection screen to sign it here.");
        hint.getStyleClass().add("empty-state-hint");
        hint.setWrapText(true);
        timelineBox.getChildren().addAll(icon, title, hint);
    }

    private void renderFatalWorkflow(String detail) {
        VBox banner = new VBox(6);
        banner.getStyleClass().addAll("card", "wf-banner--danger");
        banner.getChildren().addAll(
                new Label("This document contains an invalid or unsupported signing"
                        + " workflow."),
                new Label(detail));
        timelineBox.getChildren().add(banner);
    }

    private void showFatal(String header, String detail) {
        showAlert(Alert.AlertType.ERROR, header, detail);
    }

    private String describeStatus(EffectiveWorkflowState effective,
                                  WorkflowManifest manifest) {
        return switch (effective.status()) {
            case NOT_STARTED -> "Workflow detected — waiting for the first signature"
                    + " (" + manifest.stepsInOrder().size() + " signer(s)).";
            case IN_PROGRESS -> "Workflow detected — step "
                    + effective.currentStepOrder() + " of "
                    + manifest.stepsInOrder().size() + ".";
            case COMPLETED -> "Workflow detected — all signatures completed.";
            case SIGNATURE_INVALID, MANIFEST_INCONSISTENT ->
                    "Workflow document detected — signing is disabled.";
        };
    }

    private void showAlert(Alert.AlertType type, String header, String text) {
        Alert alert = new Alert(type);
        alert.setTitle(header);
        alert.setHeaderText(header);
        alert.setContentText(text);
        alert.initOwner(dialog);
        alert.showAndWait();
    }

    /** Minimal view of the current step for messages. */
    private record WorkflowSignerStepView(int order, String name, String fingerprint) {
    }
}
