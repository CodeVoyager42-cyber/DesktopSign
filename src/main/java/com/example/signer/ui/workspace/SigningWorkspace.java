package com.example.signer.ui.workspace;

import com.example.signer.pdf.PdfRenderService;
import com.example.signer.pdf.RenderedPdfPage;
import com.example.signer.signing.PdfSigningService;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.signing.SigningResult;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.verification.PdfVerificationService;
import com.example.signer.verification.SignatureVerificationResult;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Professional PDF signing workspace built around three zones:
 *
 * <ol>
 *   <li><b>Header</b> — brand, Open PDF, live document chip and security status.</li>
 *   <li><b>Document workspace</b> — the PDF viewer with page navigation, zoom,
 *       fit-to-width / fit-to-page and signature placement overlay.</li>
 *   <li><b>Security + action bar</b> — token/certificate/PIN controls and the
 *       primary signing workflow (Place signature → Sign → Download).</li>
 * </ol>
 *
 * <p>Coordinate mapping: the page is rendered at {@code 72 * renderScale} DPI
 * so one PDF point equals {@code renderScale} screen pixels. The overlay
 * rectangle is kept in image-pixel space and converted back to PDF points
 * (bottom-left origin) when committing the placement. {@code renderScale}
 * always reflects the actual rendered image — including fit-to-width/page mode.
 *
 * <p>Shortcuts: Ctrl+O upload, Ctrl+S download, Page Up/Down navigate,
 * Ctrl++ / Ctrl+- zoom, Ctrl+0 reset zoom, Enter unlocks the device,
 * Esc cancels a placement in progress.
 */
public class SigningWorkspace {

    private static final Logger log = LoggerFactory.getLogger(SigningWorkspace.class);

    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 4.0;
    private static final double MIN_BOX_SIZE_PX = 12;

    private final TokenService tokenService;
    private final PdfSigningService pdfSigningService;
    private final PdfRenderService pdfRenderService;
    private final PdfVerificationService pdfVerificationService;

    // ---- document state ----
    private Path originalPdf;
    private Path displayedPdf;
    private RenderedPdfPage rendered;
    private int currentPage;
    private double zoom = 1.0;
    private double renderScale = 1.0;
    private boolean fitWidthMode = true;
    private boolean fitPageMode;
    private double lastViewportWidth = -1;
    private double lastViewportHeight = -1;
    private SignaturePlacement placement;
    private Path signedPdfPath;
    private boolean signedDone;
    private boolean placingMode;
    private boolean busy;
    private boolean pdfLoadInProgress;

    // ---- UI components ----
    private Stage stage;
    private ScrollPane scrollPane;
    private AnchorPane pageLayer;
    private ImageView pageView;
    private Rectangle signatureBox;
    private VBox emptyOverlay;
    private StackPane centerPane;
    private HBox docChip;
    private ToolBar docToolbar;
    private ToolBar securityBar;
    private Label docTitleLabel;
    private Label docMetaLabel;
    private Label headerSecurityLabel;
    private Label placementBanner;

    private Button uploadButton;
    private Button prevButton;
    private Button nextButton;
    private Button zoomOutButton;
    private Button zoomInButton;
    private ToggleButton fitWidthButton;
    private ToggleButton fitPageButton;
    private Button placeButton;
    private Button clearPlacementButton;
    private Button signButton;
    private Button downloadButton;
    private Button authenticateButton;
    private ComboBox<String> certCombo;
    private PasswordField pinField;
    private Label pageLabel;
    private Label zoomLabel;
    private Label authPill;
    private Label boxInfoLabel;
    private Label statusLabel;
    private ProgressIndicator busySpinner;
    private Label signatureBadge;
    private boolean signedVerified;

    // ---- render coalescing (prevents render storms freezing the app) ----
    private boolean renderInFlight;
    private boolean renderQueued;
    private int autoFitLaps;

    // ---- drag state ----
    private enum DragMode { NONE, CREATE, MOVE, RESIZE }

    private DragMode dragMode = DragMode.NONE;
    private double pressX;
    private double pressY;
    private double startX;
    private double startY;
    private double startW;
    private double startH;

    public SigningWorkspace(TokenService tokenService,
                            PdfSigningService pdfSigningService,
                            PdfRenderService pdfRenderService,
                            PdfVerificationService pdfVerificationService) {
        this.tokenService = tokenService;
        this.pdfSigningService = pdfSigningService;
        this.pdfRenderService = pdfRenderService;
        this.pdfVerificationService = pdfVerificationService;
    }

    public void show(Stage owner) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }
        buildUi(owner);
        stage.initOwner(owner);
        stage.show();
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused && stage.isShowing()) {
                // Token may have been unlocked/removed in another window.
                refreshTokenUi();
            }
        });
        uploadButton.requestFocus();
        setStatus("Open or drop a PDF to begin signing.");
        refreshTokenUi();
        detectToken();
    }

    // ------------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------------

    private void buildUi(Stage owner) {
        // ============ 1. HEADER ============
        Label appLogo = new Label("\u2713");
        appLogo.getStyleClass().setAll("app-logo");
        Label appName = new Label("SignFlow");
        appName.getStyleClass().add("app-name");
        Label appTagline = new Label("PDF Digital Signing");
        appTagline.getStyleClass().add("app-tagline");
        HBox brand = new HBox(10, appLogo, new VBox(1, appName, appTagline));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("app-brand");

        uploadButton = button("Open PDF", "Open an existing PDF (Ctrl+O)");
        uploadButton.getStyleClass().add("btn-secondary");
        uploadButton.setOnAction(e -> uploadPdf());

        // Document chip (hidden until a PDF is open)
        Label docDot = new Label("\u25CF");
        docDot.getStyleClass().add("doc-dot");
        docTitleLabel = new Label("document.pdf");
        docTitleLabel.getStyleClass().add("doc-title");
        docTitleLabel.setMaxWidth(320);
        docTitleLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        docMetaLabel = new Label("0 pages");
        docMetaLabel.getStyleClass().add("doc-meta");
        docChip = new HBox(8, docDot, new VBox(1, docTitleLabel, docMetaLabel));
        docChip.setAlignment(Pos.CENTER_LEFT);
        docChip.getStyleClass().add("doc-chip");
        docChip.setManaged(false);
        docChip.setVisible(false);

        headerSecurityLabel = new Label("\u25CF No token");
        headerSecurityLabel.getStyleClass().setAll("security-label", "status-pill--error");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox headerBar = new HBox(12,
                brand, divider(), uploadButton, docChip, headerSpacer, headerSecurityLabel);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.getStyleClass().add("app-header");
        // Make header bar draggable to move window
        makeDraggable(headerBar, stage);

        // ============ 2. DOCUMENT TOOLBAR (visible only with a document) ============
        prevButton = button("\u25C2", "Previous page (Page Up)");
        prevButton.getStyleClass().add("nav-btn");
        prevButton.setOnAction(e -> goToPage(currentPage - 1));

        pageLabel = new Label("No document");
        pageLabel.getStyleClass().add("page-label");

        nextButton = button("\u25B8", "Next page (Page Down)");
        nextButton.getStyleClass().add("nav-btn");
        nextButton.setOnAction(e -> goToPage(currentPage + 1));

        zoomOutButton = button("\u2212", "Zoom out (Ctrl+\u2212)");
        zoomOutButton.getStyleClass().add("nav-btn");
        zoomOutButton.setOnAction(e -> setZoom(zoom - 0.25));
        zoomInButton = button("+", "Zoom in (Ctrl++)");
        zoomInButton.getStyleClass().add("nav-btn");
        zoomInButton.setOnAction(e -> setZoom(zoom + 0.25));
        zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().add("zoom-label");

        fitWidthButton = new ToggleButton("Fit width");
        fitWidthButton.getStyleClass().add("nav-btn");
        fitWidthButton.setSelected(fitWidthMode);
        fitWidthButton.setTooltip(new Tooltip("Fit the page to the window width"));
        fitWidthButton.setOnAction(e -> toggleFitWidth());

        fitPageButton = new ToggleButton("Fit page");
        fitPageButton.getStyleClass().add("nav-btn");
        fitPageButton.setSelected(false);
        fitPageButton.setTooltip(new Tooltip("Fit the whole page into the window"));
        fitPageButton.setOnAction(e -> toggleFitPage());

        ToolBar docToolbar = new ToolBar();
        docToolbar.getStyleClass().add("doc-toolbar");
        docToolbar.getItems().addAll(
                prevButton, pageLabel, nextButton,
                new Separator(),
                zoomOutButton, zoomLabel, zoomInButton,
                new Separator(),
                fitWidthButton, fitPageButton
        );
        docToolbar.setManaged(false);
        docToolbar.setVisible(false);

        // ============ 3. DOCUMENT WORKSPACE ============
        pageView = new ImageView();
        pageView.setPreserveRatio(true);

        signatureBox = new Rectangle();
        signatureBox.getStyleClass().add("signature-box");
        signatureBox.setVisible(false);

        signatureBadge = new Label("SIGNATURE");
        signatureBadge.getStyleClass().setAll("signature-label");
        signatureBadge.setAlignment(Pos.CENTER);
        signatureBadge.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        signatureBadge.setWrapText(true);
        signatureBadge.setVisible(false);

        pageLayer = new AnchorPane();
        pageLayer.getChildren().addAll(pageView, signatureBox, signatureBadge);
        pageLayer.getStyleClass().add("page-layer");
        AnchorPane.setTopAnchor(pageView, 0.0);
        AnchorPane.setLeftAnchor(pageView, 0.0);
        AnchorPane.setTopAnchor(signatureBox, 0.0);
        AnchorPane.setLeftAnchor(signatureBox, 0.0);

        pageLayer.setOnMousePressed(this::onMousePressed);
        pageLayer.setOnMouseDragged(this::onMouseDragged);
        pageLayer.setOnMouseReleased(this::onMouseReleased);
        pageLayer.setOnMouseMoved(this::onMouseMoved);

        scrollPane = new ScrollPane(pageLayer);
        scrollPane.setPannable(true);
        scrollPane.setFitToWidth(false);
        scrollPane.setFitToHeight(false);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;"
                + "-fx-padding: 0; -fx-border-color: transparent;");
        scrollPane.viewportBoundsProperty().addListener((obs, o, n) -> {
            if (displayedPdf == null || (!fitWidthMode && !fitPageMode)) {
                return;
            }
            double w = n.getWidth();
            double h = n.getHeight();
            if (w > 0 && h > 0
                    && (Math.abs(w - lastViewportWidth) > 8
                        || Math.abs(h - lastViewportHeight) > 8)) {
                lastViewportWidth = w;
                lastViewportHeight = h;
                renderCurrentPage();
            }
        });

        emptyOverlay = buildEmptyOverlay();

        placementBanner = new Label("Click and drag on the document to place your signature.");
        placementBanner.getStyleClass().add("placement-banner");
        placementBanner.setVisible(false);

        centerPane = new StackPane(scrollPane, emptyOverlay, placementBanner);
        centerPane.getStyleClass().add("workspace-canvas");
        StackPane.setAlignment(placementBanner, Pos.TOP_CENTER);
        StackPane.setMargin(placementBanner, new Insets(12, 0, 0, 0));

        // ============ 4. SECURITY BAR (ToolBar with overflow) ============
        Label securityTitle = new Label("SECURITY");
        securityTitle.getStyleClass().add("security-title");

        authPill = new Label("No token");
        authPill.getStyleClass().add("status-pill");

        certCombo = new ComboBox<>();
        certCombo.setPromptText("Signing certificate");
        certCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(certCombo, Priority.ALWAYS);

        pinField = new PasswordField();
        pinField.setPromptText("Device PIN");
        pinField.setPrefWidth(170);
        pinField.setMinWidth(120);
        pinField.setOnAction(e -> authenticate());

        authenticateButton = button("Unlock Device", "Authenticate with the security device PIN (Enter)");
        authenticateButton.getStyleClass().add("btn-secondary");
        authenticateButton.setOnAction(e -> authenticate());

        ToolBar securityBar = new ToolBar();
        securityBar.getStyleClass().add("security-bar");
        securityBar.getItems().addAll(
                securityTitle,
                authPill,
                certCombo,
                pinField,
                authenticateButton
        );
        HBox.setHgrow(certCombo, Priority.ALWAYS);

        // ============ 5. ACTION BAR (ToolBar with overflow) ============
        busySpinner = new ProgressIndicator();
        busySpinner.setPrefSize(16, 16);
        busySpinner.setVisible(false);
        busySpinner.setManaged(false);
        busySpinner.getStyleClass().add("busy-spinner");

        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        boxInfoLabel = new Label("No signature box");
        boxInfoLabel.getStyleClass().add("box-info");
        boxInfoLabel.setPrefWidth(180);
        boxInfoLabel.setMinWidth(100);

        clearPlacementButton = button("Reset", "Remove the signature box");
        clearPlacementButton.getStyleClass().add("btn-ghost");
        clearPlacementButton.setOnAction(e -> clearPlacement());

        placeButton = button("Place Signature", "Draw a signature box on the page");
        placeButton.getStyleClass().add("btn-secondary");
        placeButton.setOnAction(e -> togglePlacingMode());

        signButton = button("Sign Document", "Embed a digital signature at the drawn box");
        signButton.getStyleClass().add("btn-primary");
        signButton.setOnAction(e -> sign());

        downloadButton = button("Download Signed PDF", "Save the signed copy to a file (Ctrl+S)");
        downloadButton.getStyleClass().add("btn-secondary");
        downloadButton.setOnAction(e -> saveSignedPdf());

        ToolBar actionBar = new ToolBar();
        actionBar.getStyleClass().add("action-bar");
        actionBar.getItems().addAll(
                busySpinner,
                statusLabel,
                boxInfoLabel,
                new Separator(),
                clearPlacementButton,
                placeButton,
                signButton,
                downloadButton
        );
        // Make status label grow to fill available space
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        // ============ ROOT ============
        BorderPane root = new BorderPane();
        root.setTop(new VBox(headerBar, docToolbar));
        root.setCenter(centerPane);
        root.setBottom(new VBox(securityBar, actionBar));

        Scene scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(
                getClass().getResource("/ui/application.css").toExternalForm());
        setupKeyboard(scene);
        setupDropTarget(scene, centerPane);

        stage = new Stage();
        stage.setTitle("PDF Signing Workspace");
        stage.setScene(scene);
        stage.setMinWidth(720);
        stage.setMinHeight(500);
        stage.setOnCloseRequest(e -> log.info("Workspace closed"));
    }

    private VBox buildEmptyOverlay() {
        Label icon = new Label("\uD83D\uDCC4");
        icon.getStyleClass().add("empty-state-icon");
        Label title = new Label("No document open");
        title.getStyleClass().add("empty-state-title");
        Label hint = new Label(
                "Upload a PDF to begin signing your document.");
        hint.getStyleClass().add("empty-state-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(460);
        Button upload = new Button("Upload PDF");
        upload.getStyleClass().add("btn-primary");
        upload.setOnAction(e -> uploadPdf());
        Label dragHint = new Label("or drag and drop a PDF here");
        dragHint.getStyleClass().add("empty-state-drag");
        VBox box = new VBox(12, icon, title, hint, upload, dragHint);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("empty-state");
        return box;
    }

    private static Button button(String text, String tip) {
        Button button = new Button(text);
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    private static Node divider() {
        Region divider = new Region();
        divider.getStyleClass().add("toolbar-divider");
        return divider;
    }

    private void makeDraggable(Node header, Stage stage) {
        final Delta dragDelta = new Delta();
        header.setOnMousePressed(e -> {
            dragDelta.x = stage.getX() - e.getScreenX();
            dragDelta.y = stage.getY() - e.getScreenY();
            header.setCursor(Cursor.MOVE);
        });
        header.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() + dragDelta.x);
            stage.setY(e.getScreenY() + dragDelta.y);
        });
        header.setOnMouseReleased(e -> header.setCursor(Cursor.DEFAULT));
        header.setOnMouseExited(e -> header.setCursor(Cursor.DEFAULT));
    }

    private static class Delta { double x, y; }

    private void setupKeyboard(Scene scene) {
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() || e.isMetaDown()) {
                switch (e.getCode()) {
                    case O -> {
                        e.consume();
                        uploadPdf();
                    }
                    case S -> {
                        e.consume();
                        saveSignedPdf();
                    }
                    case EQUALS, ADD -> {
                        e.consume();
                        setZoom(zoom + 0.25);
                    }
                    case MINUS, SUBTRACT -> {
                        e.consume();
                        setZoom(zoom - 0.25);
                    }
                    case DIGIT0 -> {
                        e.consume();
                        setZoom(1.0);
                    }
                    default -> {
                        // ignore other shortcuts
                    }
                }
                return;
            }
            switch (e.getCode()) {
                case PAGE_UP -> {
                    e.consume();
                    goToPage(currentPage - 1);
                }
                case PAGE_DOWN -> {
                    e.consume();
                    goToPage(currentPage + 1);
                }
                case ESCAPE -> {
                    if (placingMode) {
                        e.consume();
                        togglePlacingMode();
                    }
                }
                default -> {
                    // ignore other keys
                }
            }
        });
    }

    // ------------------------------------------------------------------------
    // document load & rendering
    // ------------------------------------------------------------------------

    private void uploadPdf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a PDF to sign");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        openPdf(file.toPath());
    }

    private void openPdf(Path pdf) {
        if (busy || pdfLoadInProgress) {
            return;
        }
        pdfLoadInProgress = true;
        setBusy(true);
        setStatus("Loading \u201C" + pdf.getFileName() + "\u201D\u2026");
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return pdfRenderService.getPageCount(pdf);
            }
        };
        task.setOnSucceeded(e -> {
            pdfLoadInProgress = false;
            int count = task.getValue();
            originalPdf = pdf;
            displayedPdf = pdf;
            currentPage = 0;
            zoom = 1.0;
            renderScale = 1.0;
            zoomLabel.setText("100%");
            placement = null;
            signedPdfPath = null;
            signedDone = false;
            signedVerified = false;
            signatureBox.setVisible(false);
            signatureBadge.setVisible(false);
            docTitleLabel.setText(pdf.getFileName().toString());
            docMetaLabel.setText(count + " page" + (count == 1 ? "" : "s"));
            setStatus("\u2713 PDF loaded: \u201C" + pdf.getFileName() + "\u201D · " + count + " page(s)");
            renderCurrentPage();
        });
        task.setOnFailed(e -> {
            pdfLoadInProgress = false;
            setBusy(false);
            setError("Could not open PDF: " + failureMessage(task));
        });
        new Thread(task, "pdf-workspace-open").start();
    }

    private void setupDropTarget(Scene scene, StackPane target) {
        scene.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (e.getGestureSource() == null && db.hasFiles() && hasPdfFile(db)) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!target.getStyleClass().contains("drop-target")) {
                    target.getStyleClass().add("drop-target");
                }
            }
            e.consume();
        });
        scene.setOnDragExited(e -> {
            target.getStyleClass().remove("drop-target");
        });
        scene.setOnDragDropped(e -> {
            boolean done = false;
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                File pdf = db.getFiles().stream()
                        .filter(f -> f.getName().toLowerCase().endsWith(".pdf"))
                        .findFirst().orElse(null);
                if (pdf != null) {
                    openPdf(pdf.toPath());
                    done = true;
                }
            }
            target.getStyleClass().remove("drop-target");
            e.setDropCompleted(done);
            e.consume();
        });
    }

    private static boolean hasPdfFile(Dragboard db) {
        return db.getFiles().stream().anyMatch(f -> f.getName().toLowerCase().endsWith(".pdf"));
    }

    private void renderCurrentPage() {
        if (displayedPdf == null) {
            return;
        }
        // Only one render is ever in flight; additional requests (user zoom,
        // resize events, page turns) are coalesced into a single follow-up.
        if (renderInFlight) {
            renderQueued = true;
            return;
        }
        startRenderTask();
    }

    private void startRenderTask() {
        renderInFlight = true;
        renderQueued = false;
        setBusy(true);
        Path pdf = displayedPdf;
        int index = currentPage;
        renderScale = effectiveZoom();
        Task<RenderedPdfPage> task = new Task<>() {
            @Override
            protected RenderedPdfPage call() throws Exception {
                return pdfRenderService.renderPage(pdf, index, 72f * (float) renderScale);
            }
        };
        task.setOnSucceeded(e -> {
            rendered = task.getValue();
            currentPage = rendered.pageIndex();
            pageView.setImage(SwingFXUtils.toFXImage(rendered.image(), null));
            pageView.setFitWidth(-1);
            pageView.setFitHeight(-1);
            pageLayer.setPrefSize(rendered.imageWidth(), rendered.imageHeight());
            pageLayer.setMinSize(rendered.imageWidth(), rendered.imageHeight());
            drawOverlay();
            if (fitWidthMode || fitPageMode) {
                double fit = effectiveZoom();
                zoomLabel.setText(percentLabel(fit));
                // One corrective pass so the initial render (made at the stale
                // 100% scale before layout) settles at the true fit; capped to
                // keep a pathological viewport feedback loop from ever spinning.
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
            scrollPane.setHvalue(0);
            scrollPane.setVvalue(0);
            setBusy(false);
            if (renderQueued) {
                renderCurrentPage();
            }
        });
        task.setOnFailed(e -> {
            renderInFlight = false;
            autoFitLaps = 0;
            setBusy(false);
            setError("Could not render page: " + failureMessage(task));
            log.warn("Page render failed", task.getException());
            if (renderQueued) {
                renderCurrentPage();
            }
        });
        new Thread(task, "pdf-workspace-render").start();
    }

    private void goToPage(int index) {
        if (rendered == null || index < 0 || index > rendered.pageCount() - 1) {
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

    /** Current rendering scale: manual zoom, fit-to-width, or fit-to-page. */
    private double effectiveZoom() {
        if (!fitWidthMode && !fitPageMode) {
            return zoom;
        }
        if (rendered == null) {
            return zoom;
        }
        double visibleW = scrollPane.getViewportBounds().getWidth();
        double visibleH = scrollPane.getViewportBounds().getHeight();
        if (visibleW <= 0) {
            visibleW = 800;
        }
        if (visibleH <= 0) {
            visibleH = 600;
        }
        double fitW = visibleW / rendered.viewportWidthPoints();
        double fitH = visibleH / rendered.viewportHeightPoints();
        double fit = fitWidthMode ? fitW : Math.min(fitW, fitH);
        return Math.max(0.1, Math.min(MAX_ZOOM, fit));
    }

    private static String percentLabel(double value) {
        return Math.round(value * 100) + "%";
    }

    private void toggleFitWidth() {
        if (busy) {
            fitWidthButton.setSelected(fitWidthMode);
            return;
        }
        fitWidthMode = fitWidthButton.isSelected();
        if (fitWidthMode) {
            fitPageMode = false;
            fitPageButton.setSelected(false);
        }
        renderCurrentPage();
    }

    private void toggleFitPage() {
        if (busy) {
            fitPageButton.setSelected(fitPageMode);
            return;
        }
        fitPageMode = fitPageButton.isSelected();
        if (fitPageMode) {
            fitWidthMode = false;
            fitWidthButton.setSelected(false);
        }
        renderCurrentPage();
    }

    private void updatePageLabel() {
        if (rendered == null) {
            pageLabel.setText("No document");
        } else {
            pageLabel.setText("Page " + (rendered.pageIndex() + 1) + " / " + rendered.pageCount());
        }
    }

    // ------------------------------------------------------------------------
    // placement overlay
    // ------------------------------------------------------------------------

    private void togglePlacingMode() {
        placingMode = !placingMode;
        if (placingMode) {
            setStatus("Placement on — drag on the document to draw (or replace) the signature box.");
            pageLayer.setCursor(Cursor.CROSSHAIR);
        } else {
            pageLayer.setCursor(Cursor.DEFAULT);
            setStatus("Placement off — drag the box to move it, or its corner to resize.");
        }
        updateChromeVisibility();
        updateActionButtons();
    }

    private void clearPlacement() {
        placement = null;
        signatureBox.setVisible(false);
        signatureBadge.setVisible(false);
        setStatus("Signature box cleared.");
        updateActionButtons();
    }

    private void onMouseMoved(MouseEvent e) {
        if (placingMode) {
            pageLayer.setCursor(Cursor.CROSSHAIR);
            return;
        }
        if (rendered == null || busy || !signatureBox.isVisible()) {
            pageLayer.setCursor(Cursor.DEFAULT);
            return;
        }
        if (nearBottomRightCorner(e.getX(), e.getY())) {
            pageLayer.setCursor(Cursor.SE_RESIZE);
        } else if (isInsideBox(e.getX(), e.getY())) {
            pageLayer.setCursor(Cursor.OPEN_HAND);
        } else {
            pageLayer.setCursor(Cursor.DEFAULT);
        }
    }

    private void onMousePressed(MouseEvent e) {
        if (rendered == null || busy || dragMode != DragMode.NONE) {
            return;
        }
        double px = e.getX();
        double py = e.getY();
        pressX = px;
        pressY = py;
        if (placingMode) {
            dragMode = DragMode.CREATE;
            signatureBox.setX(px);
            signatureBox.setY(py);
            signatureBox.setWidth(0);
            signatureBox.setHeight(0);
            signatureBox.setVisible(true);
            return;
        }
        if (signatureBox.isVisible()) {
            if (nearBottomRightCorner(px, py)) {
                startX = signatureBox.getX();
                startY = signatureBox.getY();
                startW = signatureBox.getWidth();
                startH = signatureBox.getHeight();
                dragMode = DragMode.RESIZE;
            } else if (isInsideBox(px, py)) {
                startX = signatureBox.getX();
                startY = signatureBox.getY();
                dragMode = DragMode.MOVE;
            }
        }
    }

    private void onMouseDragged(MouseEvent e) {
        if (dragMode == DragMode.NONE || rendered == null) {
            return;
        }
        double px = e.getX();
        double py = e.getY();
        if (dragMode == DragMode.CREATE) {
            signatureBox.setX(Math.min(pressX, px));
            signatureBox.setY(Math.min(pressY, py));
            signatureBox.setWidth(Math.abs(px - pressX));
            signatureBox.setHeight(Math.abs(py - pressY));
        } else if (dragMode == DragMode.MOVE) {
            double dx = px - pressX;
            double dy = py - pressY;
            signatureBox.setX(Math.max(0, startX + dx));
            signatureBox.setY(Math.max(0, startY + dy));
        } else if (dragMode == DragMode.RESIZE) {
            signatureBox.setWidth(Math.max(MIN_BOX_SIZE_PX, startW + (px - startX)));
            signatureBox.setHeight(Math.max(MIN_BOX_SIZE_PX, startH + (py - startY)));
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (dragMode == DragMode.NONE) {
            return;
        }
        if (dragMode == DragMode.CREATE
                && signatureBox.isVisible()
                && signatureBox.getWidth() >= 4
                && signatureBox.getHeight() >= 4) {
            commitPlacement();
        } else if (dragMode == DragMode.CREATE) {
            signatureBox.setVisible(false);
        }
        dragMode = DragMode.NONE;
        placingMode = false;
        pageLayer.setCursor(Cursor.DEFAULT);
        updateChromeVisibility();
        updateActionButtons();
    }

    private boolean isInsideBox(double px, double py) {
        return px >= signatureBox.getX() - 2
                && px <= signatureBox.getX() + signatureBox.getWidth() + 2
                && py >= signatureBox.getY() - 2
                && py <= signatureBox.getY() + signatureBox.getHeight() + 2;
    }

    private boolean nearBottomRightCorner(double px, double py) {
        double hit = Math.max(8, Math.min(14, signatureBox.getWidth() / 5));
        double bx = signatureBox.getX() + signatureBox.getWidth();
        double by = signatureBox.getY() + signatureBox.getHeight();
        return Math.abs(px - bx) <= hit && Math.abs(py - by) <= hit;
    }

    private void commitPlacement() {
        float scale = (float) renderScale;
        float x = (float) (signatureBox.getX() / scale);
        float w = (float) (signatureBox.getWidth() / scale);
        float h = (float) (signatureBox.getHeight() / scale);
        float y = rendered.viewportHeightPoints() - (float) (signatureBox.getY() / scale) - h;
        y = Math.max(0f, y);
        placement = new SignaturePlacement(currentPage, x, y, w, h);
        log.info("Placement committed at page={} x={} y={} w={} h={} (page={}x{} pts)",
                currentPage, x, y, w, h,
                rendered.viewportWidthPoints(), rendered.viewportHeightPoints());
        setStatus("\u2713 Signature box placed — you can now sign the document.");
        updateSignatureBadge();
    }

    private void drawOverlay() {
        if (placement == null || rendered == null || placement.pageIndex() != currentPage) {
            signatureBox.setVisible(false);
            signatureBadge.setVisible(false);
            return;
        }
        float scale = (float) renderScale;
        signatureBox.setX(placement.x() * scale);
        signatureBox.setY(rendered.viewportHeightPoints() * scale
                - (placement.y() + placement.height()) * scale);
        signatureBox.setWidth(placement.width() * scale);
        signatureBox.setHeight(placement.height() * scale);
        signatureBox.setVisible(true);
        updateSignatureBadge();
    }

    // ------------------------------------------------------------------------
    // authentication (idempotent: safe across windows, re-clicks and dups)
    // ------------------------------------------------------------------------

    private void authenticate() {
        TokenState state = tokenService.getState();
        if (isAuthenticatedState(state)) {
            setStatus("\u2713 Security device is already unlocked — you can sign documents.");
            refreshTokenUi();
            return;
        }
        if (state != TokenState.CERTIFICATE_AVAILABLE && state != TokenState.WAITING_FOR_PIN) {
            setError("Device not ready (state " + state
                    + ") — detect the device first.");
            return;
        }
        char[] pin = pinField.getCharacters() == null ? null
                : pinField.getCharacters().toString().toCharArray();
        if (pin == null || pin.length == 0) {
            setError("Enter the device PIN first");
            showAlert(Alert.AlertType.WARNING, "PIN required",
                    "Type the security device PIN (1234 for the mock token) in the PIN field, then click Unlock Device.");
            return;
        }
        pinField.clear();
        setBusy(true);
        setStatus("Unlocking security device\u2026");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    // Only OPEN the PIN flow when it has not been started yet;
                    // authenticating again after a failed attempt keeps the
                    // current WAITING_FOR_PIN state.
                    if (tokenService.getState() == TokenState.CERTIFICATE_AVAILABLE) {
                        tokenService.startAuthentication();
                    }
                    tokenService.authenticate(pin); // TokenService wipes the PIN
                    return null;
                } finally {
                    Arrays.fill(pin, '\0');
                }
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            refreshTokenUi();
            setStatus("\u2713 Security device unlocked — place a signature box and sign the document.");
        });
        task.setOnFailed(e -> {
            setBusy(false);
            refreshTokenUi();
            setError("Authentication failed: " + failureMessage(task));
            log.warn("Authentication failed", task.getException());
            showAlert(Alert.AlertType.ERROR, "Authentication failed",
                    failureMessage(task));
        });
        new Thread(task, "workspace-auth").start();
    }

    private void refreshTokenUi() {
        List<SigningCertificate> certs = safeCertificates();
        List<String> labels = certs.stream()
                .map(c -> c.getSubject() + "  [" + c.getAlias() + "]")
                .toList();
        certCombo.getItems().setAll(labels);
        if (!labels.isEmpty() && certCombo.getSelectionModel().getSelectedIndex() < 0) {
            certCombo.getSelectionModel().select(0);
        }
        updateActionButtons();
    }

    private void detectToken() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                tokenService.detect();
                return null;
            }
        };
        task.setOnSucceeded(e -> refreshTokenUi());
        task.setOnFailed(e -> {
            log.warn("Workspace token detection failed", task.getException());
            refreshTokenUi();
        });
        new Thread(task, "workspace-token-detect").start();
    }

    private String selectedAlias() {
        int index = certCombo.getSelectionModel().getSelectedIndex();
        List<SigningCertificate> certs = safeCertificates();
        return index >= 0 && index < certs.size() ? certs.get(index).getAlias() : null;
    }

    // ------------------------------------------------------------------------
    // signing + verification + download
    // ------------------------------------------------------------------------

    private void sign() {
        String alias = selectedAlias();
        if (displayedPdf == null) {
            setStatus("Open a PDF first.");
            return;
        }
        if (placement == null) {
            setStatus("Place a signature box on the page first.");
            return;
        }
        if (!isAuthenticatedState(tokenService.getState())) {
            setError("Security device not unlocked — enter the PIN and click Unlock Device.");
            showAlert(Alert.AlertType.WARNING, "Security device locked",
                    "Unlock the device with its PIN first (1234 for the mock token).");
            return;
        }
        setBusy(true);
        setStatus("Signing\u2026");
        Path source = displayedPdf;
        String aliasF = alias;
        SignaturePlacement pl = placement;
        Task<SignOutcome> task = new Task<>() {
            @Override
            protected SignOutcome call() throws Exception {
                Path temp = Files.createTempFile("signer-workspace-", "-signed.pdf");
                try {
                    SigningResult result = pdfSigningService.signPdf(
                            source, aliasF, SigningAlgorithm.RSA_SHA256, temp, pl);
                    if (Files.size(temp) == 0) {
                        throw new IOException("Signing produced an empty output file");
                    }
                    return new SignOutcome(temp, result);
                } catch (Exception ex) {
                    Files.deleteIfExists(temp);
                    throw ex;
                }
            }
        };
        task.setOnSucceeded(e -> {
            SignOutcome outcome = task.getValue();
            signedPdfPath = outcome.path;
            displayedPdf = outcome.path;
            signedDone = true;
            placingMode = false;
            pageLayer.setCursor(Cursor.DEFAULT);
            setStatus("Signed by " + outcome.result.signerSubject() + " — verifying\u2026");
            log.info("Workspace signing succeeded, output {} ({} bytes)",
                    outcome.path, nullSafeSize(outcome.path));
            verifySigned();
            renderCurrentPage();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            setError("Signing failed: " + failureMessage(task));
            log.warn("Workspace signing failed", task.getException());
            showAlert(Alert.AlertType.ERROR, "Signing failed", failureMessage(task));
            updateActionButtons();
        });
        new Thread(task, "workspace-sign").start();
    }

    private void verifySigned() {
        Path pdf = signedPdfPath;
        Task<SignatureVerificationResult> task = new Task<>() {
            @Override
            protected SignatureVerificationResult call() throws Exception {
                return pdfVerificationService.verifyPdf(pdf);
            }
        };
        task.setOnSucceeded(e -> {
            SignatureVerificationResult vr = task.getValue();
            if (vr.valid()) {
                signedVerified = true;
                updateSignatureBadge();
                setSuccess("Signature verified  ·  " + vr.signerSubject()
                        + "  —  Download the signed PDF (Ctrl+S).");
            } else {
                setError("Signature invalid: " + vr.message());
            }
        });
        task.setOnFailed(e -> {
            log.warn("Workspace verification failed", task.getException());
            setError("Verification failed: " + failureMessage(task));
        });
        new Thread(task, "workspace-verify").start();
    }

    private void saveSignedPdf() {
        if (signedPdfPath == null) {
            setStatus("Sign a PDF first, then download the signed copy.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save signed PDF");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        String name = originalPdf != null && originalPdf.getFileName() != null
                ? originalPdf.getFileName().toString() : "document.pdf";
        int dot = name.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? name.substring(0, dot) : name) + "-signed.pdf");
        File target = chooser.showSaveDialog(stage);
        if (target == null) {
            return;
        }
        try {
            Files.copy(signedPdfPath, target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setSuccess("Signed PDF saved to " + target.getName());
        } catch (IOException ex) {
            setError("Could not save signed PDF: " + ex.getMessage());
        }
    }

    private record SignOutcome(Path path, SigningResult result) {
    }

    // ------------------------------------------------------------------------
    // state helpers
    // ------------------------------------------------------------------------

    private void setBusy(boolean b) {
        busy = b;
        busySpinner.setVisible(b);
        busySpinner.setManaged(b);
        updateActionButtons();
    }

    private void updateActionButtons() {
        TokenState state = tokenService.getState();
        boolean authed = isAuthenticatedState(state);
        boolean hasCerts = !safeCertificates().isEmpty();
        boolean hasDoc = displayedPdf != null;
        boolean hasPlacement = placement != null;

        uploadButton.setDisable(busy);
        prevButton.setDisable(busy || rendered == null || currentPage <= 0);
        nextButton.setDisable(busy || rendered == null
                || currentPage >= rendered.pageCount() - 1);
        zoomOutButton.setDisable(busy || !hasDoc || zoom <= MIN_ZOOM + 0.001);
        zoomInButton.setDisable(busy || !hasDoc || zoom >= MAX_ZOOM - 0.001);
        fitWidthButton.setDisable(busy || !hasDoc);
        fitPageButton.setDisable(busy || !hasDoc);
        placeButton.setDisable(busy || !hasDoc || signedDone);
        clearPlacementButton.setDisable(busy || !hasPlacement);
        signButton.setDisable(busy || !hasDoc || !hasPlacement || !authed || signedDone);
        downloadButton.setDisable(busy || signedPdfPath == null);
        certCombo.setDisable(busy || !hasCerts);
        pinField.setDisable(busy || !hasCerts || authed);
        authenticateButton.setDisable(busy || !hasCerts || authed);

        authenticateButton.setText(authed ? "Unlocked" : "Unlock Device");
        placeButton.setText(placingMode ? "Cancel Placement" : "Place Signature");

        updatePageLabel();
        updateAuthPill(state);
        updateBoxInfo();
        updateChromeVisibility();
    }

    /** Show/hide zones that depend on document / token presence. */
    private void updateChromeVisibility() {
        boolean hasDoc = displayedPdf != null;
        TokenState state = tokenService.getState();
        boolean hasToken = state != TokenState.NO_TOKEN && state != TokenState.TOKEN_REMOVED;

        docChip.setManaged(hasDoc);
        docChip.setVisible(hasDoc);
        docToolbar.setManaged(hasDoc);
        docToolbar.setVisible(hasDoc);
        emptyOverlay.setVisible(!hasDoc);
        securityBar.setManaged(hasToken);
        securityBar.setVisible(hasToken);
        placementBanner.setVisible(placingMode && hasDoc);
    }

    private void updateAuthPill(TokenState state) {
        boolean authed = isAuthenticatedState(state);
        boolean pending = state == TokenState.WAITING_FOR_PIN
                || state == TokenState.CERTIFICATE_AVAILABLE
                || state == TokenState.TOKEN_INITIALIZED
                || state == TokenState.TOKEN_DETECTED;

        String barText;
        String headerText;
        String variant;
        if (authed) {
            barText = "Authenticated";
            headerText = "\u25CF Authenticated";
            variant = "status-pill--active";
        } else if (pending) {
            barText = state == TokenState.WAITING_FOR_PIN ? "PIN required" : "Token detected";
            headerText = state == TokenState.WAITING_FOR_PIN
                    ? "\u25CF PIN required" : "\u25CF Token detected";
            variant = "status-pill--pending";
        } else {
            barText = "No token";
            headerText = "\u25CF No token";
            variant = "status-pill--error";
        }

        authPill.setText(barText);
        authPill.getStyleClass().removeIf(c -> c.startsWith("status-pill")
                && !c.equals("status-pill"));
        authPill.getStyleClass().add(variant);

        headerSecurityLabel.getStyleClass().removeIf(c -> c.startsWith("status-pill"));
        headerSecurityLabel.getStyleClass().add("security-label");
        headerSecurityLabel.getStyleClass().add(variant);
        headerSecurityLabel.setText(headerText);
    }

    private void updateBoxInfo() {
        if (placement == null) {
            boxInfoLabel.setText("No signature box");
            return;
        }
        String suffix = signedVerified ? "  ·  verified"
                : (signedDone ? "  ·  verifying\u2026" : "");
        boxInfoLabel.setText(String.format("Box · %dx%d pt @ (%.0f, %.0f) · page %d%s",
                Math.round(placement.width()), Math.round(placement.height()),
                placement.x(), placement.y(), placement.pageIndex() + 1, suffix));
    }

    private static boolean isAuthenticatedState(TokenState state) {
        return state == TokenState.AUTHENTICATED
                || state == TokenState.SIGNATURE_COMPLETE
                || state == TokenState.SIGNATURE_FAILED;
    }

    private List<SigningCertificate> safeCertificates() {
        try {
            return tokenService.getCertificates();
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().remove("status-text--error");
        statusLabel.getStyleClass().remove("status-text--success");
    }

    private void setSuccess(String message) {
        statusLabel.setText(message.startsWith("\u2713") ? message : "\u2713 " + message);
        statusLabel.getStyleClass().remove("status-text--error");
        if (!statusLabel.getStyleClass().contains("status-text--success")) {
            statusLabel.getStyleClass().add("status-text--success");
        }
    }

    private void setError(String message) {
        statusLabel.setText(
                message.startsWith("\u2715") || message.startsWith("\u26A0")
                        ? message : "\u2715 " + message);
        statusLabel.getStyleClass().remove("status-text--success");
        if (!statusLabel.getStyleClass().contains("status-text--error")) {
            statusLabel.getStyleClass().add("status-text--error");
        }
    }

    private void updateSignatureBadge() {
        if (placement == null || rendered == null || placement.pageIndex() != currentPage) {
            signatureBadge.setVisible(false);
            return;
        }
        float scale = (float) renderScale;
        float boxLeft = placement.x() * scale;
        float boxTop = rendered.viewportHeightPoints() * scale
                - (placement.y() + placement.height()) * scale;
        float boxW = placement.width() * scale;

        double labelW = Math.max(40, boxW - 8);
        signatureBadge.setPrefWidth(labelW);
        signatureBadge.setMaxWidth(labelW);
        signatureBadge.setLayoutX(boxLeft + 4);
        signatureBadge.setLayoutY(Math.max(0f, boxTop + 4));
        if (signedVerified) {
            signatureBadge.setText("SIGNED & VERIFIED");
            if (!signatureBadge.getStyleClass().contains("signature-label--success")) {
                signatureBadge.getStyleClass().add("signature-label--success");
            }
        } else {
            signatureBadge.setText("SIGNATURE");
            signatureBadge.getStyleClass().remove("signature-label--success");
        }
        signatureBadge.setVisible(true);
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private static String failureMessage(Task<?> task) {
        Throwable t = task.getException();
        return t == null ? "unknown error"
                : (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
    }

    private static long nullSafeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return -1;
        }
    }
}