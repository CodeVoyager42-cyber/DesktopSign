package com.example.signer.ui.workspace;

import com.example.signer.exception.ErrorCode;
import com.example.signer.token.TokenException;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.beans.binding.Bindings;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.OverrunStyle;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import com.example.signer.ui.support.Background;
import com.example.signer.pdf.DocumentAnalysis;
import com.example.signer.pdf.PdfDocumentService;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.signing.SignatureAppearance;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.signing.SignatureStampRenderer;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.ui.support.ErrorMessages;
import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.support.UiSupport;
import com.example.signer.workflow.Signer;
import com.example.signer.workflow.SigningMode;
import com.example.signer.workflow.SigningStatus;
import com.example.signer.workflow.SigningWorkflow;
import com.example.signer.workflow.WorkflowAuditTrail;
import com.example.signer.workflow.WorkflowSigningEngine;
import com.example.signer.workflow.WorkflowSigningEngine.SignerRevision;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-signer signing workspace (§3/§27): every signer of the workflow
 * reviews the current document revision, places their visible signature
 * anywhere, previews the real stamp, authenticates their PKCS#11 token and
 * signs — one signer at a time, with previous signatures shown as read-only
 * overlays and the whole chain re-verified after every revision.
 *
 * <p>Sequence per signer: review → place → configure → unlock token →
 * confirm → sign → verify → next signer → final validation screen.</p>
 */
public class
MultiSignerWorkspaceFlow {

    private static final Logger log = LoggerFactory.getLogger(MultiSignerWorkspaceFlow.class);

    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 4.0;
    private static final double MIN_BOX_SIZE_PX = 12;
    static final float DEFAULT_BOX_W_PTS = 170f;
    static final float DEFAULT_BOX_H_PTS = 55f;

    private final SigningWorkflow workflow;
    private final WorkflowSigningEngine engine;
    private final TokenService tokenService;
    private final PdfRenderService renderService;
    private final PdfDocumentService documentService;
    private final WorkflowAuditTrail audit = new WorkflowAuditTrail();
    private final Path originalPdf;
    private final String documentName;
    private final DocumentAnalysis analysis;

    private final List<SignerRevision> revisions = new ArrayList<>();
    private final List<SignaturePlacement> placements = new ArrayList<>();
    private final List<SigningStatus> signerStatuses = new ArrayList<>();
    private Path latestRevision;
    /** The shared document viewer for the current revision (one per revision). */
    private PlacementViewer viewer;
    private final StackPane viewerHolder = new StackPane();
    /** Host buttons shown at the end of every viewer's toolbar. */
    private Button docInfoButton;
    private Button signaturesButton;
    private boolean busy;

    // ---- UI ----
    private Stage stage;
    private BorderPane root;
    private Label statusLabel;
    private ProgressIndicator busyIndicator;
    private final List<Label> signerChips = new ArrayList<>();
    private SignerPanel panel;
    private Path pendingOutput;
    private boolean workflowComplete;

    public MultiSignerWorkspaceFlow(SigningMode mode,
                                    Path pdfToSign,
                                    WorkflowSigningEngine engine,
                                    TokenService tokenService,
                                    PdfRenderService renderService,
                                    PdfDocumentService documentService) {
        this.workflow = new SigningWorkflow(mode);
        this.engine = engine;
        this.tokenService = tokenService;
        this.renderService = renderService;
        this.documentService = documentService;
        this.originalPdf = pdfToSign;
        this.latestRevision = pdfToSign;
        this.documentName = pdfToSign.getFileName().toString();
        this.analysis = documentService.analyze(pdfToSign);
        for (int i = 0; i < workflow.signerCount(); i++) {
            signerStatuses.add(SigningStatus.PENDING);
            placements.add(null);
        }
    }

    /** @return the final verified PDF, or null when the workflow was cancelled */
    public Path getFinalPdf() {
        return workflowComplete ? latestRevision : null;
    }

    /** Shows the workspace and runs the whole workflow to completion. */
    public void showAndWait(Window owner) {
        stage = new Stage(StageStyle.DECORATED);
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle((workflow.signerCount() == 1 ? "Sign — " : "Multi-Signer Workspace — ")
                + documentName);
        // Responsive floors: 900x600 fits a 1366x768 laptop even at 125%
        // OS scaling (logical height ~614px) — the layout adapts below
        // (thumbnails collapse, the signer panel scrolls internally) instead
        // of clipping off-screen.
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        // ---- header: brand + document + workflow strip ----------------------
        Label logo = new Label();
        logo.setGraphic(new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        logo.getStyleClass().add("app-logo");
        Label brand = new Label("SignFlow");
        brand.getStyleClass().add("app-name");
        Label docChip = new Label(documentName);
        docChip.getStyleClass().add("mws-doc-chip");
        docChip.setMaxWidth(240);
        docChip.setTextOverrun(OverrunStyle.ELLIPSIS);
        docChip.setTooltip(new Tooltip(documentName + " · " + analysis.pageCount()
                + " page(s)" + (analysis.hasExistingSignatures()
                        ? " · " + analysis.existingSignatures().size()
                                + " existing signature(s)"
                        : "")));
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, logo, brand, docChip, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        header.setPadding(new Insets(10, 16, 10, 16));

        // The workflow strip lives on its own slim, centered row so it never
        // crowds the header at narrow widths.
        HBox stripRow = new HBox(buildStrip());
        stripRow.setAlignment(Pos.CENTER);
        stripRow.getStyleClass().add("mws-strip-row");

        // ---- document viewer: the shared PlacementViewer (thumbnails, page
        // navigation, zoom/fit, signature box gestures). It is recreated for
        // each revision in loadRevision(); these host buttons are added to
        // its toolbar each time.
        docInfoButton = new Button("", new FontIcon(FontAwesomeSolid.FILE_ALT));
        docInfoButton.getStyleClass().add("nav-btn");
        docInfoButton.setTooltip(new Tooltip("Document info — metadata and analysis"));
        docInfoButton.setOnAction(e -> showDocumentInfo());
        signaturesButton = new Button("", new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        signaturesButton.getStyleClass().add("nav-btn");
        signaturesButton.setTooltip(new Tooltip("Signatures — details and revision history"));
        signaturesButton.setOnAction(e -> showSignaturesDialog());
        HBox.setHgrow(viewerHolder, Priority.ALWAYS);

        // ---- assembly --------------------------------------------------------
        panel = new SignerPanel(new PanelHost(), tokenService);

        HBox center = new HBox(10, viewerHolder, panel.getRoot());
        center.getStyleClass().add("mws-center");
        center.setPadding(new Insets(10, 12, 10, 12));

        // Small busy spinner next to the status text: real activity feedback
        // during rendering/authentication/signing, never a fake progress bar.
        ProgressIndicator busySpinner = new ProgressIndicator();
        busySpinner.getStyleClass().add("mws-busy");
        busySpinner.setPrefSize(18, 18);
        busySpinner.setVisible(false);
        busyIndicator = busySpinner;
        statusLabel = new Label("Preparing workflow…");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        HBox bottom = new HBox(10, busyIndicator, statusLabel);
        bottom.getStyleClass().add("status-bar");
        bottom.setPadding(new Insets(8, 16, 8, 16));

        VBox topBox = new VBox(header, stripRow);
        root = new BorderPane();
        root.setTop(topBox);
        root.setCenter(center);
        root.setBottom(bottom);
        root.getStyleClass().add("mws-root");

        // Scene size respects the screen: on small/HiDPI displays the window
        // opens smaller instead of larger-than-screen (and stage min keeps it
        // usable).
        double[] winSize = fitToScreen(1280, 820);
        Scene scene = new Scene(root, winSize[0], winSize[1]);
        ThemeManager.instance().attach(scene);
        // (The viewer collapses its own thumbnail rail on narrow windows.)
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
        stage.setOnCloseRequest(e -> {
            if (!confirmCancel()) {
                e.consume();
            }
        });

        stage.setScene(scene);
        UiSupport.applyAppIcon(stage);
        audit.record("Workflow created: " + workflow.signerCount() + " signer(s)"
                + ", document " + documentName);
        if (analysis.hasExistingSignatures()) {
            audit.record("Document already contains " + analysis.existingSignatures().size()
                    + " signature(s); additional signatures create new revisions");
        }
        loadRevision(latestRevision, true);
        startTurn();
        stage.showAndWait();
        closeRenderer();
    }

    // ------------------------------------------------------------------ strip
    private HBox buildStrip() {
        HBox strip = new HBox(6);
        strip.getStyleClass().add("mws-strip");
        for (int i = 0; i < workflow.signerCount(); i++) {
            Label chip = new Label((i + 1) + " · Signer " + (i + 1));
            chip.getStyleClass().addAll("mws-chip", "mws-chip--pending");
            // Long names ellipsize instead of stretching the strip; the
            // tooltip always carries the full name and status.
            chip.setMaxWidth(180);
            chip.setTextOverrun(OverrunStyle.ELLIPSIS);
            signerChips.add(chip);
            strip.getChildren().add(chip);
            if (i < workflow.signerCount() - 1) {
                FontIcon arrow = new FontIcon(FontAwesomeSolid.CHEVRON_RIGHT);
                arrow.getStyleClass().add("mws-strip-arrow");
                strip.getChildren().add(arrow);
            }
        }
        return strip;
    }

    private void updateStrip() {
        for (int i = 0; i < signerChips.size(); i++) {
            Label chip = signerChips.get(i);
            chip.getStyleClass().removeAll("mws-chip--pending", "mws-chip--active",
                    "mws-chip--done", "mws-chip--failed");
            SigningStatus status = signerStatuses.get(i);
            String name = workflow.getSigner(i + 1).getDisplayName();
            switch (status) {
                case SIGNED -> {
                    chip.getStyleClass().add("mws-chip--done");
                    chip.setText("\u2713 " + name);
                }
                case FAILED -> {
                    chip.getStyleClass().add("mws-chip--failed");
                    chip.setText("\u2715 " + name);
                }
                default -> {
                    Signer current = workflow.getCurrentSigner();
                    if (current != null && current.getOrder() == i + 1) {
                        chip.getStyleClass().add("mws-chip--active");
                        chip.setText("\u25CF " + name);
                    } else {
                        chip.getStyleClass().add("mws-chip--pending");
                        chip.setText(name);
                    }
                }
            }
            String tip = "Signer " + (i + 1) + " — " + name + " (" + status + ")";
            chip.setTooltip(new Tooltip(tip));
        }
        if (panel != null) {
            panel.refreshStatePill();
        }
    }

    /** Success pulse on the signer's chip and the panel pill after signing. */
    private void celebrateSigned(int order) {
        pulseSuccess(signerChips.get(order - 1));
        panel.celebratePill();
    }

    /** Staggered pulse across every chip when the whole workflow completes. */
    private void celebrateWorkflow() {
        for (int i = 0; i < signerChips.size(); i++) {
            Label chip = signerChips.get(i);
            PauseTransition delay = new PauseTransition(Duration.millis(i * 90));
            delay.setOnFinished(e -> pulseSuccess(chip));
            delay.play();
        }
    }

    // ---------------------------------------------------------- turn lifecycle
    private void startTurn() {
        Signer current = workflow.getCurrentSigner();
        if (current == null) {
            completeWorkflow();
            return;
        }
        engine.prepareCurrentSigner(workflow); // release the token slot for this signer
        detectInBackground();
        panel.refreshFor(current);
        setStatus((workflow.signerCount() == 1
                ? "Review the document, place your signature, then unlock your token."
                : "Now signing: " + current.getDisplayName() + " (turn "
                        + current.getOrder() + " of " + workflow.signerCount()
                        + ") — review the document, place your signature, then unlock your token."));
        audit.record("Signer " + current.getOrder() + " turn started");
        updateStrip();
    }

    /**
     * Shows {@code pdf} (the current revision) in a fresh shared viewer.
     * Earlier signers' boxes are drawn read-only; the current signer's box
     * (if any) is restored; the page stays where it was unless
     * {@code resetPage}.
     */
    private void loadRevision(Path pdf, boolean resetPage) {
        int page = viewer == null || resetPage ? 0 : viewer.currentPage();
        closeRenderer();
        int currentOrder = currentOrder();
        List<SignaturePlacement> earlier = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            if (placements.get(i) != null && i + 1 < currentOrder) {
                earlier.add(placements.get(i));
            }
        }
        viewer = new PlacementViewer(renderService, pdf, earlier, null, currentPlacement());
        viewer.startAtPage(page);
        viewer.addToolbarActions(docInfoButton, signaturesButton);
        viewer.setInteractive(!busy);
        viewer.statusProperty().addListener((o, was, text) -> {
            if (text != null && !text.isBlank()) {
                setStatus(text);
            }
        });
        viewer.placementProperty().addListener((o, was, placement) -> onPlacementChanged(placement));
        viewer.placingProperty().addListener((o, was, now) -> panel.showPlacing(now));
        viewerHolder.getChildren().setAll(viewer.getRoot());
    }

    /** The box was drawn, moved, resized or cleared in the viewer. */
    private void onPlacementChanged(SignaturePlacement placement) {
        int idx = currentOrder() - 1;
        if (placement == null) {
            if (idx >= 0 && idx < placements.size()) {
                placements.set(idx, null);
            }
            panel.setPlacementLabelCleared();
            panel.showOverlapWarning(false);
            panel.refreshButtons();
            return;
        }
        placements.set(idx, placement);
        panel.setPlacement(placement);
        boolean overlap = viewer.overlapsExisting();
        panel.showOverlapWarning(overlap);
        if (!overlap) {
            setStatus("\u2713 Signature placed — Page " + (placement.pageIndex() + 1)
                    + ". You can drag to move it, or drag its corner to resize.");
        }
        audit.record("Signer " + currentOrder() + " selected a signature location (page "
                + (placement.pageIndex() + 1) + ")");
    }

    /** One-shot scale pulse marking a node as freshly succeeded. */
    static void pulseSuccess(Node node) {
        ScaleTransition grow = new ScaleTransition(Duration.millis(140), node);
        grow.setToX(1.12);
        grow.setToY(1.12);
        ScaleTransition settle = new ScaleTransition(Duration.millis(180), node);
        settle.setToX(1.0);
        settle.setToY(1.0);
        grow.setOnFinished(e -> settle.playFromStart());
        grow.playFromStart();
    }

    private void togglePlacing() {
        if (viewer != null) {
            viewer.togglePlacing();
        }
    }

    // ------------------------------------------------------------- token / sign
    private void detectInBackground() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                tokenService.detect();
                return null;
            }
        };
        task.setOnSucceeded(e -> panel.refreshTokenUi());
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            log.warn("Token detection failed: {}", t == null ? "unknown" : t.toString());
            setError(detectionProblem(t));
            panel.refreshTokenUi();
        });
        Background.run("mws-detect", task);
    }

    private int currentOrder() {
        Signer current = workflow.getCurrentSigner();
        return current == null ? placements.size() : current.getOrder();
    }

    private SignaturePlacement currentPlacement() {
        int idx = currentOrder() - 1;
        return idx >= 0 && idx < placements.size() ? placements.get(idx) : null;
    }

    /** Staged authentication (§34): a real token login, nothing simulated. */
    void authenticateCurrent() {
        Signer current = workflow.getCurrentSigner();
        if (current == null || busy) {
            return;
        }
        // A previous failed attempt left this signer FAILED; return them to
        // READY so this retry is legal — without this, one wrong PIN would
        // brick the turn (every later attempt would be rejected as an
        // illegal status transition).
        if (current.getStatus() == SigningStatus.FAILED) {
            workflow.resetSigner(current.getOrder());
            updateStrip();
        }
        TokenState state = tokenService.getState();
        if (state != TokenState.CERTIFICATE_AVAILABLE
                && state != TokenState.WAITING_FOR_PIN) {
            showAlert(Alert.AlertType.WARNING, "Token not detected",
                    "Please connect the signer's token and try again.");
            return;
        }
        String alias = panel.selectedAlias();
        if (alias == null) {
            showAlert(Alert.AlertType.WARNING, "No certificate selected",
                    "Select the signing certificate to use for signer "
                            + current.getOrder() + ".");
            return;
        }
        char[] pin = panel.pinChars();
        if (pin == null || pin.length == 0) {
            showAlert(Alert.AlertType.WARNING, "PIN required",
                    "Type the token PIN for signer " + current.getOrder() + ".");
            return;
        }
        panel.clearPin();
        setBusy(true);
        panel.setProgress("Authenticating token…");
        audit.record("Signer " + current.getOrder() + " authenticating");
        engine.assignCertificate(workflow, alias);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                engine.authenticate(workflow, pin); // PIN wiped by TokenService
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            panel.setProgress("Token unlocked — review and sign.");
            audit.record("Signer " + current.getOrder() + " authenticated");
            panel.refreshTokenUi();
            panel.refreshIdentityAfterAuth();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable t = task.getException();
            log.warn("Signer {} authentication failed", current.getOrder(), t);
            panel.setProgress("Authentication failed — you can retry.");
            audit.record("Signer " + current.getOrder() + " authentication failed");
            updateStrip(); // the strip chip must show the failure, not stay active
            panel.refreshTokenUi();
            showAlert(Alert.AlertType.ERROR, "Authentication failed",
                    t instanceof TokenException te
                            ? ErrorMessages.token(te)
                            : ErrorMessages.generic(t));
        });
        Background.run("mws-auth-" + current.getOrder(), task);
    }

    /** Opens the review checkpoint (§31/§32/§57) and signs on confirmation. */
    void confirmAndSign() {
        Signer current = workflow.getCurrentSigner();
        if (current == null || busy) {
            return;
        }
        SignaturePlacement placement = currentPlacement();
        if (placement == null) {
            showAlert(Alert.AlertType.WARNING, "No signature placed",
                    "Place your signature on the document first.");
            return;
        }
        if (tokenService.getState() != TokenState.AUTHENTICATED
                && tokenService.getState() != TokenState.SIGNATURE_COMPLETE) {
            showAlert(Alert.AlertType.WARNING, "Token locked",
                    "Unlock the signer's token before signing.");
            return;
        }
        if (!showCheckpoint(current, placement)) {
            return;
        }
        setBusy(true);
        panel.setProgress("Creating cryptographic signature…");
        int order = current.getOrder();
        Path input = latestRevision;
        Path output;
        try {
            output = Files.createTempFile("signed-" + order + "-", ".pdf");
        } catch (IOException ex) {
            setBusy(false);
            setError("Could not create the revision file: " + ex.getMessage());
            return;
        }
        pendingOutput = output;
        audit.record("Signer " + order + " signing (page "
                + (placement.pageIndex() + 1) + ")");
        Task<SignerRevision> task = new Task<>() {
            @Override
            protected SignerRevision call() throws Exception {
                return engine.sign(workflow, input, placement, output,
                        panel.appearance());
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            SignerRevision revision = task.getValue();
            revisions.add(revision);
            placements.set(order - 1, placement);
            signerStatuses.set(order - 1, SigningStatus.SIGNED);
            latestRevision = output;
            audit.record("Signer " + order + " signed; chain verified ("
                    + revision.verification().signatures().size()
                    + " signature(s) valid)");
            log.info("Signer {} completed revision {}", order, output.getFileName());
            updateStrip();
            celebrateSigned(order);
            if (!workflow.isComplete()) {
                loadRevision(latestRevision, false);
                startTurn();
            } else {
                completeWorkflow();
            }
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable t = task.getException();
            log.warn("Signer {} signing failed", order, t);
            panel.setProgress("Signing failed — you can retry.");
            audit.record("Signer " + order + " signing failed");
            // Same brick as the PIN case: the engine marked this signer
            // FAILED; reset so the retry (re-authenticate → sign) is legal.
            if (workflow.getSigner(order).getStatus() == SigningStatus.FAILED) {
                workflow.resetSigner(order);
            }
            updateStrip();
            if (t instanceof WorkflowSigningEngine.ChainVerificationException chain) {
                showAlert(Alert.AlertType.ERROR, "Signature verification failed",
                        "The previous signature could not be verified.\n\n"
                                + chain.getRevision().verification().message()
                                + "\n\nThe document will not continue to the next "
                                + "signing step.");
            } else {
                showAlert(Alert.AlertType.ERROR, "Signature creation failed",
                        "The document could not be signed by this signer.\n\n"
                                + ErrorMessages.generic(t));
            }
        });
        Background.run("mws-sign-" + order, task);
    }

    private void completeWorkflow() {
        workflowComplete = true;
        try {
            tokenService.disconnect();
        } catch (RuntimeException e) {
            log.debug("Disconnect after completion: {}", e.getMessage());
        }
        audit.record("Workflow completed: all " + workflow.signerCount()
                + " signature(s) created and verified");
        updateStrip();
        celebrateWorkflow();
        showCompletion();
        setStatus(workflow.signerCount() == 1
                ? "\u2713 Document signed and verified."
                : "\u2713 Document successfully signed by all "
                        + workflow.signerCount() + " signer(s).");
    }

    // ---------------------------------------------------------------- dialogs
    private boolean showCheckpoint(Signer signer, SignaturePlacement placement) {
        SignatureAppearance appearance = panel.appearance();
        return SigningCheckpoint.confirm(stage,
                SwingFXUtils.toFXImage(SignatureStampRenderer.render(DEFAULT_BOX_W_PTS,
                        DEFAULT_BOX_H_PTS, certificateName(signer), algorithmFor(signer),
                        appearance), null),
                List.of(new SigningCheckpoint.Row("Signer", signer.getDisplayName()),
                        new SigningCheckpoint.Row("Certificate", certificateName(signer)),
                        new SigningCheckpoint.Row("Page", String.valueOf(placement.pageIndex() + 1)),
                        new SigningCheckpoint.Row("Position", positionLabel(placement)),
                        new SigningCheckpoint.Row("Reason", appearance.reason()),
                        new SigningCheckpoint.Row("Location", appearance.location()),
                        new SigningCheckpoint.Row("Token", tokenService.getTokenInfo().label())));
    }

    private void showDocumentInfo() {
        var d = analysis;
        VBox content = new VBox(6,
                sectionTitle("Document Information"),
                row("File name", d.file().fileName()),
                row("File size", humanSize(d.file().sizeBytes())),
                row("Pages", String.valueOf(d.pageCount())),
                row("PDF version", d.pdfVersion()),
                row("Title", orNotSpecified(d.metadata().title())),
                row("Author", orNotSpecified(d.metadata().author())),
                row("Subject", orNotSpecified(d.metadata().subject())),
                row("Keywords", orNotSpecified(d.metadata().keywords())),
                row("Creator", orNotSpecified(d.metadata().creator())),
                row("Producer", orNotSpecified(d.metadata().producer())),
                row("Created", orNotSpecified(dateLabel(d.metadata().created()))),
                row("Modified", orNotSpecified(dateLabel(d.metadata().modified()))),
                new Separator(),
                sectionTitle("Signatures & Fields"),
                row("Existing signatures",
                        d.hasExistingSignatures()
                                ? d.existingSignatures().size() + " — new signatures "
                                        + "will create additional revisions"
                                : "None"),
                row("Signature fields",
                        d.signatureFieldNames().isEmpty()
                                ? "None"
                                : d.signatureFieldNames().size() + " ("
                                        + d.emptySignatureFields() + " empty)"));
        scrollableDialog("Document Info", content, 460, 560);
    }

    private void showSignaturesDialog() {
        VBox content = new VBox(6, sectionTitle("Signatures"));
        if (revisions.isEmpty()) {
            content.getChildren().add(row("Status", "No signature created yet."));
        }
        for (int i = 0; i < revisions.size(); i++) {
            SignerRevision revision = revisions.get(i);
            var detail = revision.verification().signatures().isEmpty()
                    ? null
                    : revision.verification().signatures().get(i);
            content.getChildren().add(row(
                    "\u2713 Signature " + revision.order(),
                    revision.displayName() + " — "
                            + (detail == null || detail.valid() ? "Valid" : "Invalid")
                            + (detail != null && detail.signedAtKnown()
                                    ? " · signed " + dateLabel(detail.signedAt())
                                    : "")));
        }
        content.getChildren().addAll(new Separator(), sectionTitle("Document History"),
                row("Original", documentName));
        for (int i = 0; i < revisions.size(); i++) {
            SignerRevision revision = revisions.get(i);
            content.getChildren().add(row("Revision " + (i + 1),
                    "Signed by " + revision.displayName() + " ("
                            + revision.algorithm().getJcaName() + ")"));
        }
        content.getChildren().addAll(new Separator(),
                sectionTitle("Workflow Audit"));
        for (WorkflowAuditTrail.AuditEvent event : audit.events()) {
            content.getChildren().add(row(
                    event.at().toString().substring(11, 19), event.event()));
        }
        scrollableDialog("Signatures & History", content, 520, 620);
    }

    private void showCompletion() {
        VBox content = new VBox(14);
        content.getStyleClass().add("mws-completion");
        content.setPadding(new Insets(24));
        Label title = new Label("\u2713 Document Successfully Signed");
        title.getStyleClass().add("mws-completion-title");
        Label doc = new Label(documentName);
        doc.getStyleClass().add("mws-completion-doc");
        content.getChildren().addAll(title, doc,
                new Label(revisions.size() + " of " + workflow.signerCount()
                        + " signatures completed"));
        for (int i = 0; i < revisions.size(); i++) {
            content.getChildren().add(new Label("\u2713 "
                    + revisions.get(i).displayName()));
        }
        var last = revisions.get(revisions.size() - 1).verification();
        content.getChildren().add(new Label(last.valid()
                ? "Document integrity verified — every signature in the chain is valid."
                : "\u26A0 " + last.message()));
        Button signaturesButton = new Button("Show Signatures");
        signaturesButton.getStyleClass().add("btn-secondary");
        signaturesButton.setOnAction(e -> showSignaturesDialog());
        Button saveButton = new Button("Save Signed PDF…",
                new FontIcon(FontAwesomeSolid.DOWNLOAD));
        saveButton.getStyleClass().add("btn-primary");
        saveButton.setOnAction(e -> saveFinalPdf());
        Button doneButton = new Button("Done");
        doneButton.getStyleClass().add("btn-ghost");
        doneButton.setOnAction(e -> stage.close());
        HBox buttons = new HBox(10, signaturesButton, saveButton, doneButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(buttons);

        // A ScrollPane keeps the completion screen reachable on short
        // windows; the StackPane keeps the card centered on wide ones.
        ScrollPane completionScroll = new ScrollPane(new StackPane(content));
        completionScroll.setFitToWidth(true);
        completionScroll.getStyleClass().add("mws-dialog");
        VBox viewerColumn = new VBox(completionScroll);
        viewerColumn.getStyleClass().add("mws-completion-pane");
        HBox center = new HBox(viewerColumn);
        HBox.setHgrow(viewerColumn, Priority.ALWAYS);
        root.setCenter(center);
    }

    private void saveFinalPdf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save signed PDF");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        String base = documentName;
        int dot = base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : base)
                + "-multi-signed.pdf");
        File target = chooser.showSaveDialog(stage);
        if (target == null) {
            return;
        }
        try {
            Files.copy(latestRevision, target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            audit.record("Final signed PDF saved to " + target.getName());
            setStatus("\u2713 Signed PDF saved to " + target.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Saving the signed PDF failed", e);
            showAlert(Alert.AlertType.ERROR, "Could not save",
                    "The signed PDF could not be saved.\n"
                            + ErrorMessages.generic(e));
        }
    }

    /** The workspace's side of {@link SignerPanel.Host}. */
    private final class PanelHost implements SignerPanel.Host {
        @Override
        public SigningWorkflow workflow() {
            return workflow;
        }

        @Override
        public SignaturePlacement currentPlacement() {
            return MultiSignerWorkspaceFlow.this.currentPlacement();
        }

        @Override
        public boolean isBusy() {
            return busy;
        }

        @Override
        public void togglePlacing() {
            MultiSignerWorkspaceFlow.this.togglePlacing();
        }

        @Override
        public void clearPlacement() {
            MultiSignerWorkspaceFlow.this.clearPlacement();
        }

        @Override
        public void confirmAndSign() {
            MultiSignerWorkspaceFlow.this.confirmAndSign();
        }

        @Override
        public void authenticateCurrent() {
            MultiSignerWorkspaceFlow.this.authenticateCurrent();
        }

        @Override
        public void signerNameChanged() {
            updateStrip();
        }

        @Override
        public void setStatus(String text) {
            MultiSignerWorkspaceFlow.this.setStatus(text);
        }
    }

    // ---------------------------------------------------------------- helpers
    private void clearPlacement() {
        int idx = currentOrder() - 1;
        if (viewer != null) {
            viewer.clearPlacement(); // fires onPlacementChanged(null)
        } else if (idx >= 0 && idx < placements.size()) {
            placements.set(idx, null);
        }
        panel.setPlacementLabelCleared();
        panel.refreshButtons();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (viewer != null) {
            viewer.setInteractive(!value);
        }
        if (busyIndicator != null) {
            busyIndicator.setVisible(value);
        }
        panel.refreshButtons();
    }

    private void setStatus(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    /** What the user sees when looking for the smart card fails (never silent). */
    static String detectionProblem(Throwable t) {
        if (t instanceof TokenException te
                && (te.getErrorCode() == ErrorCode.PKCS11_LIBRARY_NOT_FOUND
                || te.getErrorCode() == ErrorCode.PKCS11_INITIALIZATION_FAILED)) {
            return "Smart card driver problem: " + te.getMessage();
        }
        return "The smart card could not be read"
                + (t == null || t.getMessage() == null ? "." : ": " + t.getMessage());
    }

    private void setError(String text) {
        Platform.runLater(() -> statusLabel.setText("\u2715 " + text));
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.initOwner(stage);
            alert.showAndWait();
        });
    }

    private void closeRenderer() {
        if (viewer != null) {
            viewer.close();
        }
    }

    /** Asks before abandoning an incomplete workflow (§66). */
    private boolean confirmCancel() {
        if (workflowComplete) {
            return true;
        }
        boolean anySigned = signerStatuses.stream()
                .anyMatch(s -> s == SigningStatus.SIGNED);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Leave the signing workspace");
        alert.setHeaderText(null);
        alert.setContentText(anySigned
                ? "Signatures created so far stay in the saved revisions, but "
                        + "the workflow will stop here. Leave now?"
                : "Leave the signing workspace? The pending signature "
                        + "placement will be discarded.");
        alert.initOwner(stage);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        audit.record("Workflow cancelled by the user after "
                + revisions.size() + " signature(s)");
        try {
            tokenService.disconnect();
        } catch (RuntimeException e) {
            log.debug("Disconnect during cancel: {}", e.getMessage());
        }
        return true;
    }

    private static void styleNavButton(Button button, String tooltip) {
        button.getStyleClass().add("nav-btn");
        button.setTooltip(new Tooltip(tooltip));
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static HBox row(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("mws-row-key");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("mws-row-value");
        valueLabel.setWrapText(true);
        // Values grow and wrap inside the dialog instead of stretching it
        // beyond the screen with long file names or paths.
        valueLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);
        HBox box = new HBox(10, keyLabel, valueLabel);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private void scrollableDialog(String title, VBox content, double w, double h) {
        Stage dialog = new Stage(StageStyle.DECORATED);
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(title);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("mws-dialog");
        double[] size = fitToScreen(w, h);
        Scene scene = new Scene(scroll, size[0], size[1]);
        dialog.setMaxWidth(size[0] + 16);
        dialog.setMaxHeight(size[1] + 39);
        ThemeManager.instance().attach(scene);
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);
        dialog.showAndWait();
    }

    private static String orNotSpecified(String value) {
        return value == null || value.isBlank() ? "Not specified" : value;
    }

    static String dateLabel(Instant instant) {
        return instant == null ? null
                : DateTimeFormatter
                        .ofPattern("dd MMM yyyy HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(instant);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** Largest (w, h) that fits the screen's visual bounds with a margin. */
    private static double[] fitToScreen(double width, double height) {
        Rectangle2D bounds =
                Screen.getPrimary().getVisualBounds();
        return new double[] {
                Math.max(320, Math.min(width, bounds.getWidth() - 48)),
                Math.max(240, Math.min(height, bounds.getHeight() - 48)),
        };
    }

    private static String positionLabel(SignaturePlacement p) {
        // Position is described relative to thirds — informative, not exact.
        return "Page " + (p.pageIndex() + 1) + ", "
                + Math.round(p.x()) + ", " + Math.round(p.y()) + " pt, "
                + Math.round(p.width()) + "×" + Math.round(p.height()) + " pt";
    }

    static String certificateName(Signer signer) {
        return signer.getCertificate()
                .map(cert -> certificateName(cert.getSubject()))
                .orElse("Signer " + signer.getOrder());
    }

    static String certificateName(String subject) {
        Matcher matcher = Pattern.compile("(?:^|,)CN=([^,]+)").matcher(subject);
        return matcher.find() ? matcher.group(1) : subject;
    }

    static SigningAlgorithm algorithmFor(Signer signer) {
        String keyAlgorithm = signer.getCertificate()
                .map(SigningCertificate::getPublicKeyAlgorithm)
                .orElse("RSA");
        return "EC".equals(keyAlgorithm)
                ? SigningAlgorithm.ECDSA_SHA256
                : SigningAlgorithm.RSA_SHA256;
    }
}
