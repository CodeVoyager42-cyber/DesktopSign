package com.example.signer.ui.workspace;

import com.example.signer.pdf.DocumentAnalysis;
import com.example.signer.pdf.DocumentRenderer;
import com.example.signer.pdf.PdfDocumentService;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.pdf.RenderedPdfPage;
import com.example.signer.signing.SignatureAppearance;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.signing.SignatureStampRenderer;
import com.example.signer.signing.SigningResult;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.ui.ErrorMessages;
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

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private static final float DEFAULT_BOX_W_PTS = 170f;
    private static final float DEFAULT_BOX_H_PTS = 55f;

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
    private DocumentRenderer renderer;

    // ---- viewer state ----
    private RenderedPdfPage rendered;
    private int currentPage;
    private double zoom = 1.0;
    private double renderScale = 1.0;
    private boolean fitWidthMode = true;
    private boolean fitPageMode;
    private boolean renderInFlight;
    private int renderGeneration;
    private boolean renderQueued;
    private int autoFitLaps;
    private boolean placingMode;

    private enum DragMode { NONE, CREATE, MOVE, RESIZE }
    private DragMode dragMode = DragMode.NONE;
    private double pressX;
    private double pressY;
    private double startX;
    private double startY;
    private double startW;
    private double startH;
    private boolean busy;

    // ---- UI ----
    private Stage stage;
    private BorderPane root;
    private ThumbnailPane thumbnails;
    private ScrollPane scrollPane;
    private AnchorPane pageLayer;
    private ImageView pageView;
    private Rectangle pendingBox;
    private final List<Rectangle> previousBoxes = new ArrayList<>();
    private Label zoomLabel;
    private Label pageLabel;
    private ToggleButton fitWidthButton;
    private ToggleButton fitPageButton;
    private ToggleButton placeButton;
    private Label statusLabel;
    private Label placeHint;
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
        stage.setTitle("Multi-Signer Workspace — " + documentName);
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
        docChip.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
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

        // ---- viewer toolbar --------------------------------------------------
        Button prevButton = new Button("", new FontIcon(FontAwesomeSolid.CHEVRON_LEFT));
        Button nextButton = new Button("", new FontIcon(FontAwesomeSolid.CHEVRON_RIGHT));
        styleNavButton(prevButton, "Previous page (Page Up)");
        styleNavButton(nextButton, "Next page (Page Down)");
        prevButton.setOnAction(e -> goToPage(currentPage - 1));
        nextButton.setOnAction(e -> goToPage(currentPage + 1));
        pageLabel = new Label("Page – of –");
        pageLabel.getStyleClass().add("mws-page-label");

        Button zoomOutButton = new Button("", new FontIcon(FontAwesomeSolid.MINUS));
        Button zoomInButton = new Button("", new FontIcon(FontAwesomeSolid.PLUS));
        styleNavButton(zoomOutButton, "Zoom out (Ctrl+−)");
        styleNavButton(zoomInButton, "Zoom in (Ctrl++)");
        zoomOutButton.setOnAction(e -> setZoom(zoom - 0.25));
        zoomInButton.setOnAction(e -> setZoom(zoom + 0.25));
        zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().add("mws-zoom-label");
        fitWidthButton = new ToggleButton("Fit width");
        fitPageButton = new ToggleButton("Fit page");
        for (ToggleButton b : List.of(fitWidthButton, fitPageButton)) {
            b.getStyleClass().add("nav-btn");
        }
        fitWidthButton.setSelected(true);
        fitWidthButton.setOnAction(e -> toggleFitWidth());
        fitPageButton.setOnAction(e -> toggleFitPage());

        // Icon-only with tooltips: text buttons here overflow the toolbar
        // once the window approaches its minimum width.
        Button docInfoButton = new Button("", new FontIcon(FontAwesomeSolid.FILE_ALT));
        docInfoButton.getStyleClass().add("nav-btn");
        docInfoButton.setTooltip(new Tooltip("Document info — metadata and analysis"));
        docInfoButton.setOnAction(e -> showDocumentInfo());
        Button signaturesButton = new Button("",
                new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        signaturesButton.getStyleClass().add("nav-btn");
        signaturesButton.setTooltip(new Tooltip("Signatures — details and revision history"));
        signaturesButton.setOnAction(e -> showSignaturesDialog());

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, prevButton, pageLabel, nextButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                zoomOutButton, zoomLabel, zoomInButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                fitWidthButton, fitPageButton,
                toolbarSpacer, docInfoButton, signaturesButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("mws-toolbar");
        toolbar.setPadding(new Insets(6, 10, 6, 10));

        // ---- viewer ----------------------------------------------------------
        pageView = new ImageView();
        pageView.setPreserveRatio(true);
        pendingBox = new Rectangle();
        pendingBox.getStyleClass().add("mws-pending-box");
        pendingBox.setVisible(false);
        pageLayer = new AnchorPane();
        pageLayer.getChildren().addAll(pageView, pendingBox);
        pageLayer.getStyleClass().add("mws-page-layer");
        pageLayer.setOnMousePressed(this::onMousePressed);
        pageLayer.setOnMouseDragged(this::onMouseDragged);
        pageLayer.setOnMouseReleased(this::onMouseReleased);

        scrollPane = new ScrollPane(new StackPane(pageLayer));
        scrollPane.setFitToWidth(false);
        scrollPane.setFitToHeight(false);
        scrollPane.setPannable(true);
        scrollPane.getStyleClass().add("mws-viewer");

        // Floating hint over the page while the user is drawing a signature.
        Label hint = new Label("Drag on the page to draw your signature box  ·  Esc to cancel");
        hint.getStyleClass().add("mws-place-hint");
        hint.setVisible(false);
        hint.setManaged(false);
        placeHint = hint;
        StackPane viewerStack = new StackPane(scrollPane, placeHint);
        StackPane.setAlignment(placeHint, Pos.TOP_CENTER);
        StackPane.setMargin(placeHint, new Insets(14, 0, 0, 0));
        VBox.setVgrow(viewerStack, Priority.ALWAYS);

        // ---- assembly --------------------------------------------------------
        panel = new SignerPanel();
        thumbnails = new ThumbnailPane(renderService);
        thumbnails.setPageClickListener(this::goToPage);

        VBox viewerColumn = new VBox(toolbar, viewerStack);
        viewerColumn.getStyleClass().add("mws-viewer-column");
        HBox.setHgrow(viewerColumn, Priority.ALWAYS);

        HBox center = new HBox(10, thumbnails, viewerColumn, panel.getRoot());
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
        // Responsive: collapse the thumbnail rail when the window gets tight;
        // the viewer reclaims the space and thumbnails come back on widen.
        scene.widthProperty().addListener((obs, was, now) -> {
            boolean showThumbs = now.doubleValue() >= 1040;
            thumbnails.setVisible(showThumbs);
            thumbnails.setManaged(showThumbs);
        });
        scene.setOnKeyPressed(e -> {
            if (e.isControlDown()) {
                switch (e.getCode()) {
                    case EQUALS, ADD -> { e.consume(); setZoom(zoom + 0.25); }
                    case MINUS, SUBTRACT -> { e.consume(); setZoom(zoom - 0.25); }
                    case DIGIT0 -> { e.consume(); setZoom(1.0); }
                    default -> { }
                }
                return;
            }
            switch (e.getCode()) {
                case PAGE_UP -> { e.consume(); goToPage(currentPage - 1); }
                case PAGE_DOWN -> { e.consume(); goToPage(currentPage + 1); }
                case ESCAPE -> { if (placingMode) { e.consume(); togglePlacing(); } }
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
            chip.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
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
        setStatus("Now signing: " + current.getDisplayName() + " (turn "
                + current.getOrder() + " of " + workflow.signerCount()
                + ") — review the document, place your signature, then unlock your token.");
        audit.record("Signer " + current.getOrder() + " turn started");
        updateStrip();
    }

    /** Loads the current revision: opens a reusable renderer, redraws pages. */
    private void loadRevision(Path pdf, boolean resetPage) {
        closeRenderer();
        // Invalidate any render still in flight against the OLD renderer:
        // its result must be discarded, or it would draw a stale page and/or
        // crash on the closed document.
        renderGeneration++;
        Task<DocumentRenderer> task = new Task<>() {
            @Override
            protected DocumentRenderer call() throws Exception {
                return renderService.openDocument(pdf);
            }
        };
        task.setOnSucceeded(e -> {
            DocumentRenderer opened = task.getValue();
            if (opened == null) {
                return;
            }
            renderer = opened;
            int pages = renderer.pageCount();
            thumbnails.load(pdf, pages);
            if (resetPage || currentPage >= pages) {
                currentPage = 0;
            }
            renderCurrentPage();
        });
        task.setOnFailed(e -> {
            log.warn("Opening the document failed", task.getException());
            setError("Could not open the document: "
                    + ErrorMessages.generic(task.getException()));
        });
        new Thread(task, "mws-open").start();
    }

    // ---------------------------------------------------------- page rendering
    private void renderCurrentPage() {
        if (renderer == null) {
            return;
        }
        if (renderInFlight) {
            renderQueued = true;
            return;
        }
        startRenderTask();
    }

    private void startRenderTask() {
        if (renderer == null) {
            return;
        }
        renderInFlight = true;
        renderQueued = false;
        setBusy(true);
        // Capture the renderer instance and generation: if the document is
        // swapped (next signer's revision) while this render runs, the result
        // of the stale task is discarded instead of crashing on the closed
        // document or overwriting the new page.
        final DocumentRenderer taskRenderer = renderer;
        final int generation = renderGeneration;
        int index = currentPage;
        renderScale = effectiveZoom();
        Task<RenderedPdfPage> task = new Task<>() {
            @Override
            protected RenderedPdfPage call() throws Exception {
                return taskRenderer.renderPage(index, 72f * (float) renderScale);
            }
        };
        task.setOnSucceeded(e -> {
            if (generation != renderGeneration) {
                // The document was swapped while this render ran (next
                // signer's revision): discard the stale result. The new
                // document's own render pass is already scheduled.
                renderInFlight = false;
                return;
            }
            RenderedPdfPage previous = rendered;
            rendered = task.getValue();
            currentPage = rendered.pageIndex();
            swapPageImage(previous == null ? null : previous.image(),
                    SwingFXUtils.toFXImage(rendered.image(), null));
            pageLayer.setPrefSize(rendered.imageWidth(), rendered.imageHeight());
            pageLayer.setMinSize(rendered.imageWidth(), rendered.imageHeight());
            pageLabel.setText("Page " + (currentPage + 1) + " of "
                    + rendered.pageCount());
            thumbnails.highlight(currentPage);
            drawOverlays();
            if (fitWidthMode || fitPageMode) {
                double fit = effectiveZoom();
                zoomLabel.setText(percentLabel(fit));
                if (Math.abs(fit - renderScale) > 0.02 * fit && autoFitLaps < 2) {
                    autoFitLaps++;
                    startRenderTask();
                    return;
                }
            } else {
                zoomLabel.setText(percentLabel(zoom));
            }
            autoFitLaps = 0;
            renderInFlight = false;
            setBusy(false);
            if (renderQueued) {
                renderCurrentPage();
            }
        });
        task.setOnFailed(e -> {
            if (generation == renderGeneration) {
                renderInFlight = false;
                autoFitLaps = 0;
                setBusy(false);
                log.warn("Page render failed", task.getException());
                setError("Could not render the page: "
                        + ErrorMessages.generic(task.getException()));
                if (renderQueued) {
                    renderCurrentPage();
                }
            }
        });
        new Thread(task, "mws-render").start();
    }

    private void goToPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.pageCount()) {
            return;
        }
        currentPage = index;
        renderCurrentPage();
    }

    private void setZoom(double newZoom) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        fitWidthMode = false;
        fitPageMode = false;
        fitWidthButton.setSelected(false);
        fitPageButton.setSelected(false);
        zoomLabel.setText(percentLabel(zoom));
        renderCurrentPage();
    }

    private double effectiveZoom() {
        if (!fitWidthMode && !fitPageMode || rendered == null) {
            return zoom;
        }
        double visibleW = Math.max(1, scrollPane.getViewportBounds().getWidth());
        double visibleH = Math.max(1, scrollPane.getViewportBounds().getHeight());
        double fitW = visibleW / rendered.viewportWidthPoints();
        double fitH = visibleH / rendered.viewportHeightPoints();
        double fit = fitWidthMode ? fitW : Math.min(fitW, fitH);
        return Math.max(0.1, Math.min(MAX_ZOOM, fit));
    }

    private void toggleFitWidth() {
        fitWidthMode = fitWidthButton.isSelected();
        if (fitWidthMode) {
            fitPageMode = false;
            fitPageButton.setSelected(false);
        }
        renderCurrentPage();
    }

    private void toggleFitPage() {
        fitPageMode = fitPageButton.isSelected();
        if (fitPageMode) {
            fitWidthMode = false;
            fitWidthButton.setSelected(false);
        }
        renderCurrentPage();
    }

    private static String percentLabel(double value) {
        return Math.round(value * 100) + "%";
    }

    // ------------------------------------------------------ overlays & gestures
    /** Draws previous signatures (read-only) and the pending placement. */
    /**
     * Swaps the rendered page image with a soft crossfade: the previous
     * revision/page dissolves out above the new render instead of
     * hard-clipping between documents.
     */
    private void swapPageImage(BufferedImage previous, Image next) {
        pageView.setImage(next);
        if (previous == null) {
            return;
        }
        ImageView ghost = new ImageView(SwingFXUtils.toFXImage(previous, null));
        ghost.setMouseTransparent(true);
        ghost.setManaged(false);
        pageLayer.getChildren().add(ghost);
        FadeTransition fade = new FadeTransition(Duration.millis(280), ghost);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> pageLayer.getChildren().remove(ghost));
        fade.play();
    }

    /** One-shot scale pulse marking a node as freshly succeeded. */
    private void pulseSuccess(Node node) {
        ScaleTransition grow = new ScaleTransition(Duration.millis(140), node);
        grow.setToX(1.12);
        grow.setToY(1.12);
        ScaleTransition settle = new ScaleTransition(Duration.millis(180), node);
        settle.setToX(1.0);
        settle.setToY(1.0);
        grow.setOnFinished(e -> settle.playFromStart());
        grow.playFromStart();
    }

    private void drawOverlays() {
        if (rendered == null) {
            return;
        }
        for (Rectangle old : previousBoxes) {
            pageLayer.getChildren().remove(old);
        }
        previousBoxes.clear();
        Signer current = workflow.getCurrentSigner();
        int currentOrder = current == null ? Integer.MAX_VALUE : current.getOrder();
        for (int i = 0; i < placements.size(); i++) {
            SignaturePlacement p = placements.get(i);
            if (p == null || p.pageIndex() != currentPage || i + 1 >= currentOrder) {
                continue;
            }
            double[] r = CoordinateMapper.toPixels(rendered, p);
            Rectangle box = new Rectangle(r[0], r[1], r[2], r[3]);
            box.getStyleClass().add("mws-previous-box");
            box.setMouseTransparent(true);
            pageLayer.getChildren().add(box);
            previousBoxes.add(box);
        }
        updatePendingBox();
    }

    private void updatePendingBox() {
        SignaturePlacement p = currentPlacement();
        if (rendered == null || p == null || p.pageIndex() != currentPage) {
            pendingBox.setVisible(false);
            return;
        }
        double[] r = CoordinateMapper.toPixels(rendered, p);
        pendingBox.setX(r[0]);
        pendingBox.setY(r[1]);
        pendingBox.setWidth(r[2]);
        pendingBox.setHeight(r[3]);
        pendingBox.setVisible(true);
    }

    private void onMousePressed(MouseEvent e) {
        if (busy || rendered == null) {
            return;
        }
        pressX = e.getX();
        pressY = e.getY();
        if (placingMode) {
            dragMode = DragMode.CREATE;
            pendingBox.setX(pressX);
            pendingBox.setY(pressY);
            pendingBox.setWidth(0);
            pendingBox.setHeight(0);
            pendingBox.setVisible(true);
            scrollPane.setPannable(false);
        } else if (pendingBox.isVisible()
                && nearBottomRightCorner(pressX, pressY)) {
            startX = pendingBox.getX();
            startY = pendingBox.getY();
            startW = pendingBox.getWidth();
            startH = pendingBox.getHeight();
            dragMode = DragMode.RESIZE;
            scrollPane.setPannable(false);
        } else if (pendingBox.isVisible() && isInsideBox(pressX, pressY)) {
            startX = pendingBox.getX();
            startY = pendingBox.getY();
            dragMode = DragMode.MOVE;
            scrollPane.setPannable(false);
            pageLayer.setCursor(Cursor.MOVE);
        } else {
            scrollPane.setPannable(true);
        }
    }

    private void onMouseDragged(MouseEvent e) {
        if (dragMode == DragMode.NONE || rendered == null) {
            return;
        }
        double px = e.getX();
        double py = e.getY();
        if (dragMode == DragMode.CREATE) {
            pendingBox.setX(Math.min(pressX, px));
            pendingBox.setY(Math.min(pressY, py));
            pendingBox.setWidth(Math.abs(px - pressX));
            pendingBox.setHeight(Math.abs(py - pressY));
        } else if (dragMode == DragMode.MOVE) {
            pendingBox.setX(Math.max(0, startX + (px - pressX)));
            pendingBox.setY(Math.max(0, startY + (py - pressY)));
        } else if (dragMode == DragMode.RESIZE) {
            pendingBox.setWidth(Math.max(MIN_BOX_SIZE_PX, startW + (px - startX)));
            pendingBox.setHeight(Math.max(MIN_BOX_SIZE_PX, startH + (py - startY)));
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (dragMode == DragMode.NONE) {
            return;
        }
        if (dragMode == DragMode.CREATE
                && pendingBox.isVisible()
                && pendingBox.getWidth() >= 4
                && pendingBox.getHeight() >= 4) {
            commitPlacement();
        } else if (dragMode == DragMode.CREATE) {
            pendingBox.setVisible(false);
        }
        dragMode = DragMode.NONE;
        placingMode = false;
        if (placeButton != null) {
            placeButton.setSelected(false);
            placeButton.setText("Place Signature");
        }
        if (placeHint != null) {
            placeHint.setVisible(false);
            placeHint.setManaged(false);
        }
        scrollPane.setPannable(true);
        pageLayer.setCursor(Cursor.DEFAULT);
    }

    /** Converts the drawn rectangle into PDF points via the shared mapper. */
    private void commitPlacement() {
        SignaturePlacement placement = CoordinateMapper.toPlacement(
                currentPage, rendered,
                pendingBox.getX(), pendingBox.getY(),
                pendingBox.getWidth(), pendingBox.getHeight());
        panel.setPlacement(placement);
        boolean overlap = false;
        for (int i = 0; i < placements.size(); i++) {
            if (i + 1 != currentOrder() && placements.get(i) != null
                    && placements.get(i).overlaps(placement)) {
                overlap = true;
            }
        }
        panel.showOverlapWarning(overlap);
        if (overlap) {
            setStatus("\u26A0 This signature overlaps an existing signature — "
                    + "consider choosing another location.");
        } else {
            setStatus("\u2713 Signature placed — Page " + (placement.pageIndex() + 1)
                    + ", " + Math.round(placement.x()) + "×"
                    + Math.round(placement.y()) + " pt. You can drag to move, "
                    + "drag the corner to resize.");
        }
        audit.record("Signer " + currentOrder() + " selected a signature location (page "
                + (placement.pageIndex() + 1) + ")");
    }

    private boolean isInsideBox(double px, double py) {
        return px >= pendingBox.getX() - 2
                && px <= pendingBox.getX() + pendingBox.getWidth() + 2
                && py >= pendingBox.getY() - 2
                && py <= pendingBox.getY() + pendingBox.getHeight() + 2;
    }

    private boolean nearBottomRightCorner(double px, double py) {
        double hit = Math.max(8, Math.min(14, pendingBox.getWidth() / 5));
        double bx = pendingBox.getX() + pendingBox.getWidth();
        double by = pendingBox.getY() + pendingBox.getHeight();
        return Math.abs(px - bx) <= hit && Math.abs(py - by) <= hit;
    }

    private void togglePlacing() {
        placingMode = !placingMode;
        placeButton.setSelected(placingMode);
        placeButton.setText(placingMode ? "Click on the page…" : "Place Signature");
        placeHint.setVisible(placingMode);
        placeHint.setManaged(placingMode);
        pageLayer.setCursor(placingMode ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        setStatus(placingMode
                ? "Drag on the page to draw your signature box. Esc cancels."
                : "Placement cancelled.");
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
            log.debug("Token detection failed", task.getException());
            panel.refreshTokenUi();
        });
        new Thread(task, "mws-detect").start();
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
                    t instanceof com.example.signer.token.TokenException te
                            ? ErrorMessages.token(te)
                            : ErrorMessages.generic(t));
        });
        new Thread(task, "mws-auth-" + current.getOrder()).start();
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
        } catch (java.io.IOException ex) {
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
        new Thread(task, "mws-sign-" + order).start();
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
        setStatus("\u2713 Document successfully signed by all "
                + workflow.signerCount() + " signer(s).");
    }

    // ---------------------------------------------------------------- dialogs
    private boolean showCheckpoint(Signer signer, SignaturePlacement placement) {
        var dialog = new Stage(StageStyle.DECORATED);
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Ready to Sign");

        ImageView preview = new ImageView(SwingFXUtils.toFXImage(
                SignatureStampRenderer.render(DEFAULT_BOX_W_PTS, DEFAULT_BOX_H_PTS,
                        certificateName(signer), algorithmFor(signer),
                        panel.appearance()),
                null));
        preview.setFitWidth(400);
        preview.setPreserveRatio(true);
        preview.getStyleClass().add("mws-checkpoint-preview");

        VBox summary = new VBox(6,
                row("Signer", signer.getDisplayName()),
                row("Certificate", certificateName(signer)),
                row("Page", String.valueOf(placement.pageIndex() + 1)),
                row("Position", positionLabel(placement)),
                row("Reason", orNotSpecified(panel.appearance().reason())),
                row("Location", orNotSpecified(panel.appearance().location())),
                row("Token", tokenService.getTokenInfo().label()));
        summary.getStyleClass().add("mws-checkpoint-summary");

        Label warning = new Label("Once signed, this document revision cannot be "
                + "modified without affecting the signature.");
        warning.getStyleClass().add("mws-checkpoint-warning");
        warning.setWrapText(true);

        CheckBox reviewed = new CheckBox(
                "I have reviewed the document and my signature placement.");
        reviewed.getStyleClass().add("mws-checkpoint-confirm");
        Button signButton = new Button("Sign Document",
                new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        signButton.getStyleClass().add("btn-primary");
        signButton.setDisable(true);
        reviewed.selectedProperty().addListener((o, was, now) ->
                signButton.setDisable(!now));
        final boolean[] confirmed = { false };
        signButton.setOnAction(e -> {
            confirmed[0] = true;
            dialog.close();
        });
        Button backButton = new Button("Back");
        backButton.getStyleClass().add("btn-ghost");
        backButton.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(10, backButton, signButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(14, preview, summary, warning, reviewed, buttons);
        content.setFillWidth(true);
        VBox.setVgrow(summary, Priority.ALWAYS);
        content.getStyleClass().add("mws-checkpoint");
        content.setPadding(new Insets(18));
        double[] dlgSize = fitToScreen(460, 560);
        Scene scene = new Scene(content, dlgSize[0], dlgSize[1]);
        ThemeManager.instance().attach(scene);
        // The real stamp preview shrinks with the dialog instead of forcing
        // a fixed 400px width onto small screens.
        preview.fitWidthProperty().bind(javafx.beans.binding.Bindings.min(
                400.0, scene.widthProperty().subtract(56)));
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);
        dialog.showAndWait();
        return confirmed[0];
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
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save signed PDF");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        String base = documentName;
        int dot = base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : base)
                + "-multi-signed.pdf");
        java.io.File target = chooser.showSaveDialog(stage);
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

    // ------------------------------------------------------------------ panel
    /** Right rail: certificate, appearance, token, placement, progress. */
    private final class SignerPanel {

        private final VBox root;
        private final Label title = new Label();
        private final Label statePill = new Label();
        private final ComboBox<String> certCombo = new ComboBox<>();
        private final Label certDetails = new Label(" ");
        private final ComboBox<String> reasonCombo = new ComboBox<>();
        private final javafx.scene.control.TextField reasonOther = new javafx.scene.control.TextField();
        private final javafx.scene.control.TextField locationField = new javafx.scene.control.TextField();
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
        private final javafx.scene.control.TextField nameField = new javafx.scene.control.TextField();
        private final Label identityName = new Label();
        private final Label identityDetails = new Label();
        private Signer boundSigner;

        SignerPanel() {
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
                    updateStrip();
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
            pinField.setOnAction(e -> authenticateCurrent());
            unlockButton.getStyleClass().add("btn-secondary");
            unlockButton.setMaxWidth(Double.MAX_VALUE);
            unlockButton.setOnAction(e -> authenticateCurrent());
            HBox pinRow = new HBox(8, pinField, unlockButton);
            HBox.setHgrow(pinField, Priority.ALWAYS);
            VBox tokenBox = new VBox(4, tokenLabel, pinRow);

            placeBtn.getStyleClass().add("btn-secondary");
            placeBtn.setMaxWidth(Double.MAX_VALUE);
            placeBtn.setOnAction(e -> togglePlacing());
            clearButton.getStyleClass().add("btn-ghost");
            clearButton.setOnAction(e -> clearPlacement());
            signButton.getStyleClass().add("btn-primary");
            signButton.setMaxWidth(Double.MAX_VALUE);
            signButton.setOnAction(e -> confirmAndSign());
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
                    + workflow.signerCount()
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
            pulseSuccess(statePill);
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
                    .map(c -> certificateName(c.getSubject()) + " ["
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
                        + dateLabel(until) + "\n" + cert.getIssuer()
                        + "\n" + cert.getPublicKeyAlgorithm() + " "
                        + cert.getKeySize() + " bit");
            }
            updatePreview();
        }

        void refreshButtons() {
            Signer current = workflow.getCurrentSigner();
            TokenState state = tokenService.getState();
            boolean unlocked = state == TokenState.AUTHENTICATED
                    || state == TokenState.SIGNATURE_COMPLETE;
            signButton.setDisable(!unlocked || currentPlacement() == null || busy);
            pinField.setDisable(unlocked);
            unlockButton.setDisable(unlocked || busy);
        }

        void setPlacement(SignaturePlacement placement) {
            placements.set(currentOrder() - 1, placement);
            placementLabel.setText("Signature: Page "
                    + (placement.pageIndex() + 1) + " · "
                    + Math.round(placement.width()) + "×"
                    + Math.round(placement.height()) + " pt");
            refreshButtons();
            updatePreview();
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
            setStatus(text);
        }

        /** Refreshes the identity block once the signer's certificate is known. */
        void refreshIdentityAfterAuth() {
            Signer signer = boundSigner;
            if (signer == null) {
                return;
            }
            identityName.setText(signer.getDisplayName());
            String certName = signer.getCertificate()
                    .map(cert -> certificateName(cert.getSubject()))
                    .orElse(null);
            identityDetails.setText("Signing turn " + signer.getOrder() + " of "
                    + workflow.signerCount()
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
            Signer current = workflow.getCurrentSigner();
            if (current == null) {
                return;
            }
            try {
                BufferedImage stamp = SignatureStampRenderer.render(
                        DEFAULT_BOX_W_PTS, DEFAULT_BOX_H_PTS,
                        certificateName(current), algorithmFor(current),
                        appearance());
                preview.setImage(SwingFXUtils.toFXImage(stamp, null));
            } catch (RuntimeException e) {
                log.debug("Preview update skipped: {}", e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- helpers
    private void clearPlacement() {
        int idx = currentOrder() - 1;
        if (idx >= 0 && idx < placements.size()) {
            placements.set(idx, null);
        }
        pendingBox.setVisible(false);
        panel.setPlacementLabelCleared();
        panel.refreshButtons();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (busyIndicator != null) {
            busyIndicator.setVisible(value);
        }
        panel.refreshButtons();
    }

    private void setStatus(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
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
        if (renderer != null) {
            renderer.close();
            renderer = null;
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

    private static String dateLabel(Instant instant) {
        return instant == null ? null
                : java.time.format.DateTimeFormatter
                        .ofPattern("dd MMM yyyy HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(instant);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** Largest (w, h) that fits the screen's visual bounds with a margin. */
    private static double[] fitToScreen(double width, double height) {
        javafx.geometry.Rectangle2D bounds =
                javafx.stage.Screen.getPrimary().getVisualBounds();
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

    private static String certificateName(Signer signer) {
        return signer.getCertificate()
                .map(cert -> certificateName(cert.getSubject()))
                .orElse("Signer " + signer.getOrder());
    }

    private static String certificateName(String subject) {
        Matcher matcher = Pattern.compile("(?:^|,)CN=([^,]+)").matcher(subject);
        return matcher.find() ? matcher.group(1) : subject;
    }

    private static SigningAlgorithm algorithmFor(Signer signer) {
        String keyAlgorithm = signer.getCertificate()
                .map(SigningCertificate::getPublicKeyAlgorithm)
                .orElse("RSA");
        return "EC".equals(keyAlgorithm)
                ? SigningAlgorithm.ECDSA_SHA256
                : SigningAlgorithm.RSA_SHA256;
    }
}
