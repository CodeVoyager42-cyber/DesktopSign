package com.example.signer.ui.workspace;

import com.example.signer.ui.support.Background;
import com.example.signer.pdf.DocumentRenderer;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.pdf.RenderedPdfPage;
import com.example.signer.signing.SignaturePlacement;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The document viewer of the "people on this computer" workspace, as a
 * reusable component: page thumbnails, toolbar (pages, zoom, fit width /
 * fit page), and the signature-box gestures — "Place Signature" then drag
 * on the page to draw the box, drag the box to move it, drag its
 * bottom-right corner to resize it. Earlier signatures are drawn as
 * read-only boxes. Same styles ({@code mws-*}) and same coordinate mapping
 * ({@link CoordinateMapper}) as {@link MultiSignerWorkspaceFlow}.
 *
 * <p>Pure presentation: it only reads the PDF to render it and exposes the
 * chosen {@link SignaturePlacement} in PDF points. Rendering runs off the FX
 * thread; a render that finishes after {@link #close()} is discarded.
 */
public final class PlacementViewer {

    private static final Logger log = LoggerFactory.getLogger(PlacementViewer.class);

    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 4.0;
    private static final double MIN_BOX_SIZE_PX = 12;
    /** Box size used when the user clicks instead of dragging, in PDF points. */
    static final float CLICK_BOX_W_PTS = 170f;
    static final float CLICK_BOX_H_PTS = 55f;

    private final PdfRenderService renderService;
    private final Path pdf;
    private final List<SignaturePlacement> existing;
    private final boolean locked;

    private final ReadOnlyObjectWrapper<SignaturePlacement> placement = new ReadOnlyObjectWrapper<>();
    private final BooleanProperty placing = new SimpleBooleanProperty();
    private final ObjectProperty<String> status = new SimpleObjectProperty<>("");

    private DocumentRenderer renderer;
    private RenderedPdfPage rendered;
    private int currentPage;
    private double zoom = 1.0;
    private double renderScale = 1.0;
    private boolean fitWidthMode = true;
    private boolean fitPageMode;
    private boolean renderInFlight;
    private boolean renderQueued;
    private int renderGeneration;
    private int autoFitLaps;
    private boolean closed;

    private enum DragMode { NONE, CREATE, MOVE, RESIZE }
    private DragMode dragMode = DragMode.NONE;
    private double pressX;
    private double pressY;
    private double startX;
    private double startY;
    private double startW;
    private double startH;

    private HBox root;
    private HBox toolbar;
    /** False while the host is busy (signing): no placement gestures. */
    private boolean interactive = true;
    /** Page to show once the document is open (when no box decides it). */
    private int startPage = -1;
    private ThumbnailPane thumbnails;
    private ScrollPane scrollPane;
    private final AnchorPane pageLayer = new AnchorPane();
    private final ImageView pageView = new ImageView();
    private final Rectangle box = new Rectangle();
    private final List<Rectangle> existingBoxes = new ArrayList<>();
    private final Label pageLabel = new Label("Page – of –");
    private final Label zoomLabel = new Label("100%");
    private final ToggleButton fitWidthButton = new ToggleButton("Fit width");
    private final ToggleButton fitPageButton = new ToggleButton("Fit page");
    private Label placeHint;

    /**
     * @param existing earlier signatures' boxes (read-only), may be empty
     * @param fixed    a placement chosen by the workflow's sender: shown and
     *                 used as-is, the user cannot move it; {@code null} lets
     *                 the user place the box
     */
    public PlacementViewer(PdfRenderService renderService, Path pdf,
                           List<SignaturePlacement> existing, SignaturePlacement fixed) {
        this(renderService, pdf, existing, fixed, null);
    }

    /**
     * @param initial a box the user placed earlier on this same document
     *                (e.g. when returning from the confirm screen); movable
     */
    public PlacementViewer(PdfRenderService renderService, Path pdf,
                           List<SignaturePlacement> existing, SignaturePlacement fixed,
                           SignaturePlacement initial) {
        this.renderService = renderService;
        this.pdf = pdf;
        this.existing = existing == null ? List.of() : List.copyOf(existing);
        this.locked = fixed != null;
        this.placement.set(fixed != null ? fixed : initial);
        buildAndOpen();
    }

    /**
     * A read-only viewer (thumbnails, page navigation, zoom, fit) with no
     * signature placement — used to review a document and its signatures.
     */
    public static PlacementViewer readOnly(PdfRenderService renderService, Path pdf) {
        return new PlacementViewer(renderService, pdf, List.of(), null, null, true);
    }

    private PlacementViewer(PdfRenderService renderService, Path pdf,
                            List<SignaturePlacement> existing, SignaturePlacement fixed,
                            SignaturePlacement initial, boolean readOnly) {
        this.renderService = renderService;
        this.pdf = pdf;
        this.existing = existing == null ? List.of() : List.copyOf(existing);
        this.locked = readOnly || fixed != null;
        this.placement.set(fixed != null ? fixed : initial);
        buildAndOpen();
    }

    private void buildAndOpen() {

        // ---- toolbar (same controls as the multi-signer workspace) ----------
        Button prev = navButton(FontAwesomeSolid.CHEVRON_LEFT, "Previous page (Page Up)");
        Button next = navButton(FontAwesomeSolid.CHEVRON_RIGHT, "Next page (Page Down)");
        prev.setOnAction(e -> goToPage(currentPage - 1));
        next.setOnAction(e -> goToPage(currentPage + 1));
        pageLabel.getStyleClass().add("mws-page-label");
        Button zoomOut = navButton(FontAwesomeSolid.MINUS, "Zoom out");
        Button zoomIn = navButton(FontAwesomeSolid.PLUS, "Zoom in");
        zoomOut.setOnAction(e -> setZoom(effectiveZoom() - 0.25));
        zoomIn.setOnAction(e -> setZoom(effectiveZoom() + 0.25));
        zoomLabel.getStyleClass().add("mws-zoom-label");
        for (ToggleButton b : List.of(fitWidthButton, fitPageButton)) {
            b.getStyleClass().add("nav-btn");
        }
        fitWidthButton.setSelected(true);
        fitWidthButton.setOnAction(e -> {
            fitWidthMode = fitWidthButton.isSelected();
            if (fitWidthMode) {
                fitPageMode = false;
                fitPageButton.setSelected(false);
            }
            renderCurrentPage();
        });
        fitPageButton.setOnAction(e -> {
            fitPageMode = fitPageButton.isSelected();
            if (fitPageMode) {
                fitWidthMode = false;
                fitWidthButton.setSelected(false);
            }
            renderCurrentPage();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar = new HBox(8, prev, pageLabel, next,
                new Separator(Orientation.VERTICAL), zoomOut, zoomLabel, zoomIn,
                new Separator(Orientation.VERTICAL), fitWidthButton, fitPageButton, spacer);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("mws-toolbar");
        toolbar.setPadding(new Insets(6, 10, 6, 10));

        // ---- page + overlays ------------------------------------------------
        pageView.setPreserveRatio(true);
        box.getStyleClass().add("mws-pending-box");
        box.setVisible(false);
        pageLayer.getChildren().addAll(pageView, box);
        pageLayer.getStyleClass().add("mws-page-layer");
        pageLayer.setOnMousePressed(this::onMousePressed);
        pageLayer.setOnMouseDragged(this::onMouseDragged);
        pageLayer.setOnMouseReleased(this::onMouseReleased);
        pageLayer.setOnMouseMoved(this::onMouseMoved);

        scrollPane = new ScrollPane(new StackPane(pageLayer));
        scrollPane.setPannable(true);
        scrollPane.getStyleClass().add("mws-viewer");
        scrollPane.viewportBoundsProperty().addListener((o, was, now) -> {
            if ((fitWidthMode || fitPageMode) && rendered != null
                    && Math.abs(was.getWidth() - now.getWidth()) > 4) {
                renderCurrentPage();
            }
        });

        placeHint = new Label("Drag on the page to draw your signature box  ·  Esc to cancel");
        placeHint.getStyleClass().add("mws-place-hint");
        placeHint.setVisible(false);
        placeHint.setManaged(false);
        StackPane viewerStack = new StackPane(scrollPane, placeHint);
        StackPane.setAlignment(placeHint, Pos.TOP_CENTER);
        StackPane.setMargin(placeHint, new Insets(14, 0, 0, 0));
        VBox.setVgrow(viewerStack, Priority.ALWAYS);

        VBox viewerColumn = new VBox(toolbar, viewerStack);
        viewerColumn.getStyleClass().add("mws-viewer-column");
        HBox.setHgrow(viewerColumn, Priority.ALWAYS);

        thumbnails = new ThumbnailPane(renderService);
        thumbnails.setPageClickListener(this::goToPage);

        root = new HBox(10, thumbnails, viewerColumn);
        root.getStyleClass().add("mws-center");
        root.sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                scene.widthProperty().addListener((w, a, now) -> {
                    boolean show = now.doubleValue() >= 1040;
                    thumbnails.setVisible(show);
                    thumbnails.setManaged(show);
                });
            }
        });
        placing.addListener((o, was, now) -> {
            placeHint.setVisible(now);
            placeHint.setManaged(now);
            pageLayer.setCursor(now ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        });
        open();
    }

    /** The thumbnails + toolbar + page, ready to embed. */
    public Node getRoot() {
        return root;
    }

    /** The chosen box in PDF points (bottom-left origin), or null. */
    public ReadOnlyObjectProperty<SignaturePlacement> placementProperty() {
        return placement.getReadOnlyProperty();
    }

    public SignaturePlacement getPlacement() {
        return placement.get();
    }

    /** True while the next drag draws a new box. */
    public BooleanProperty placingProperty() {
        return placing;
    }

    /** Short plain-language feedback after each gesture ("Signature placed — …"). */
    public ObjectProperty<String> statusProperty() {
        return status;
    }

    /** True when the sender fixed the position (the user cannot move it). */
    public boolean isLocked() {
        return locked;
    }

    /** True when the chosen box overlaps one of the earlier signatures. */
    public boolean overlapsExisting() {
        SignaturePlacement p = placement.get();
        return p != null && existing.stream().anyMatch(p::overlaps);
    }

    /** "Place Signature" button: arm (or cancel) drawing on the page. */
    public void togglePlacing() {
        if (locked || !interactive) {
            return;
        }
        placing.set(!placing.get());
        status.set(placing.get()
                ? "Drag on the page to draw your signature box. Esc cancels."
                : "Placement cancelled.");
    }

    /** Escape key: cancel drawing. */
    public void cancelPlacing() {
        if (placing.get()) {
            togglePlacing();
        }
    }

    /** Removes the box (not allowed when the sender fixed it). */
    public void clearPlacement() {
        if (locked) {
            return;
        }
        placement.set(null);
        box.setVisible(false);
        status.set("Signature removed. Click Place Signature to place it again.");
    }

    /** Shows the page that holds the box. */
    public void revealPlacement() {
        SignaturePlacement p = placement.get();
        if (p != null) {
            goToPage(p.pageIndex());
        }
    }

    public void goToPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.pageCount()) {
            return;
        }
        currentPage = index;
        renderCurrentPage();
    }

    /** Index of the page on screen. */
    public int currentPage() {
        return currentPage;
    }

    public void zoomIn() {
        setZoom(effectiveZoom() + 0.25);
    }

    public void zoomOut() {
        setZoom(effectiveZoom() - 0.25);
    }

    public void resetZoom() {
        setZoom(1.0);
    }

    /** Adds host-specific buttons at the right end of the toolbar. */
    public void addToolbarActions(Node... nodes) {
        toolbar.getChildren().addAll(nodes);
    }

    /** Opens on {@code page} instead of the first page (call right after creating). */
    public void startAtPage(int page) {
        startPage = page;
        if (renderer != null && page >= 0) {
            goToPage(page);
        }
    }

    /** Disables placement gestures (e.g. while a signature is being created). */
    public void setInteractive(boolean value) {
        interactive = value;
        if (!value) {
            placing.set(false);
            dragMode = DragMode.NONE;
        }
    }

    /** Releases the PDF; pending renders are discarded. */
    public void close() {
        closed = true;
        renderGeneration++;
        closeRenderer();
    }

    // ------------------------------------------------------------- rendering

    private void open() {
        int generation = ++renderGeneration;
        Task<DocumentRenderer> task = new Task<>() {
            @Override
            protected DocumentRenderer call() throws Exception {
                return renderService.openDocument(pdf);
            }
        };
        task.setOnSucceeded(e -> {
            DocumentRenderer opened = task.getValue();
            if (closed || generation != renderGeneration) {
                closeQuietly(opened);
                return;
            }
            renderer = opened;
            thumbnails.load(pdf, renderer.pageCount());
            SignaturePlacement p = placement.get();
            currentPage = p != null && p.pageIndex() < renderer.pageCount() ? p.pageIndex()
                    : startPage >= 0 && startPage < renderer.pageCount() ? startPage : 0;
            renderCurrentPage();
        });
        task.setOnFailed(e -> {
            log.warn("Opening the document for placement failed", task.getException());
            status.set("The document could not be displayed.");
        });
        start(task, "placement-open");
    }

    private void renderCurrentPage() {
        if (renderer == null || closed) {
            return;
        }
        if (renderInFlight) {
            renderQueued = true;
            return;
        }
        startRenderTask();
    }

    private void startRenderTask() {
        renderInFlight = true;
        renderQueued = false;
        final DocumentRenderer taskRenderer = renderer;
        final int generation = renderGeneration;
        final int index = currentPage;
        renderScale = effectiveZoom();
        Task<RenderedPdfPage> task = new Task<>() {
            @Override
            protected RenderedPdfPage call() throws Exception {
                return taskRenderer.renderPage(index, 72f * (float) renderScale);
            }
        };
        task.setOnSucceeded(e -> {
            if (closed || generation != renderGeneration) {
                renderInFlight = false;
                return;
            }
            rendered = task.getValue();
            currentPage = rendered.pageIndex();
            pageView.setImage(SwingFXUtils.toFXImage(rendered.image(), null));
            pageLayer.setPrefSize(rendered.imageWidth(), rendered.imageHeight());
            pageLayer.setMinSize(rendered.imageWidth(), rendered.imageHeight());
            pageLabel.setText("Page " + (currentPage + 1) + " of " + rendered.pageCount());
            thumbnails.highlight(currentPage);
            drawOverlays();
            if (fitWidthMode || fitPageMode) {
                double fit = effectiveZoom();
                zoomLabel.setText(percent(fit));
                if (Math.abs(fit - renderScale) > 0.02 * fit && autoFitLaps < 2) {
                    autoFitLaps++;
                    startRenderTask();
                    return;
                }
            } else {
                zoomLabel.setText(percent(zoom));
            }
            autoFitLaps = 0;
            renderInFlight = false;
            if (renderQueued) {
                renderCurrentPage();
            }
        });
        task.setOnFailed(e -> {
            renderInFlight = false;
            autoFitLaps = 0;
            if (!closed && generation == renderGeneration) {
                log.warn("Page render failed", task.getException());
                status.set("This page could not be displayed.");
                if (renderQueued) {
                    renderCurrentPage();
                }
            }
        });
        start(task, "placement-render");
    }

    private void setZoom(double value) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
        fitWidthMode = false;
        fitPageMode = false;
        fitWidthButton.setSelected(false);
        fitPageButton.setSelected(false);
        zoomLabel.setText(percent(zoom));
        renderCurrentPage();
    }

    private double effectiveZoom() {
        if (!fitWidthMode && !fitPageMode || rendered == null) {
            return zoom;
        }
        double visibleW = Math.max(1, scrollPane.getViewportBounds().getWidth() - 4);
        double visibleH = Math.max(1, scrollPane.getViewportBounds().getHeight() - 4);
        double fitW = visibleW / rendered.viewportWidthPoints();
        double fitH = visibleH / rendered.viewportHeightPoints();
        double fit = fitWidthMode ? fitW : Math.min(fitW, fitH);
        return Math.max(0.1, Math.min(MAX_ZOOM, fit));
    }

    // -------------------------------------------------------------- overlays

    private void drawOverlays() {
        pageLayer.getChildren().removeAll(existingBoxes);
        existingBoxes.clear();
        for (SignaturePlacement p : existing) {
            if (p == null || p.pageIndex() != currentPage) {
                continue;
            }
            double[] r = CoordinateMapper.toPixels(rendered, p);
            Rectangle old = new Rectangle(r[0], r[1], r[2], r[3]);
            old.getStyleClass().add("mws-previous-box");
            old.setMouseTransparent(true);
            existingBoxes.add(old);
        }
        // Earlier signatures go under the user's own box.
        pageLayer.getChildren().addAll(1, existingBoxes);
        updateBox();
    }

    private void updateBox() {
        SignaturePlacement p = placement.get();
        if (rendered == null || p == null || p.pageIndex() != currentPage) {
            box.setVisible(false);
            return;
        }
        double[] r = CoordinateMapper.toPixels(rendered, p);
        box.setX(r[0]);
        box.setY(r[1]);
        box.setWidth(r[2]);
        box.setHeight(r[3]);
        box.setVisible(true);
    }

    // -------------------------------------------------------------- gestures

    private void onMouseMoved(MouseEvent e) {
        if (placing.get() || locked || !box.isVisible()) {
            return;
        }
        pageLayer.setCursor(nearBottomRightCorner(e.getX(), e.getY()) ? Cursor.SE_RESIZE
                : isInsideBox(e.getX(), e.getY()) ? Cursor.MOVE : Cursor.DEFAULT);
    }

    private void onMousePressed(MouseEvent e) {
        if (rendered == null || locked || !interactive) {
            return;
        }
        pressX = e.getX();
        pressY = e.getY();
        if (placing.get()) {
            dragMode = DragMode.CREATE;
            box.setX(pressX);
            box.setY(pressY);
            box.setWidth(0);
            box.setHeight(0);
            box.setVisible(true);
            scrollPane.setPannable(false);
        } else if (box.isVisible() && nearBottomRightCorner(pressX, pressY)) {
            startX = box.getX();
            startY = box.getY();
            startW = box.getWidth();
            startH = box.getHeight();
            dragMode = DragMode.RESIZE;
            scrollPane.setPannable(false);
        } else if (box.isVisible() && isInsideBox(pressX, pressY)) {
            startX = box.getX();
            startY = box.getY();
            dragMode = DragMode.MOVE;
            scrollPane.setPannable(false);
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
        switch (dragMode) {
            case CREATE -> {
                box.setX(Math.min(pressX, px));
                box.setY(Math.min(pressY, py));
                box.setWidth(Math.abs(px - pressX));
                box.setHeight(Math.abs(py - pressY));
            }
            case MOVE -> {
                box.setX(Math.max(0, Math.min(startX + (px - pressX),
                        rendered.imageWidth() - box.getWidth())));
                box.setY(Math.max(0, Math.min(startY + (py - pressY),
                        rendered.imageHeight() - box.getHeight())));
            }
            case RESIZE -> {
                box.setWidth(Math.max(MIN_BOX_SIZE_PX, startW + (px - pressX)));
                box.setHeight(Math.max(MIN_BOX_SIZE_PX, startH + (py - pressY)));
            }
            default -> { }
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (dragMode == DragMode.NONE) {
            return;
        }
        DragMode finished = dragMode;
        dragMode = DragMode.NONE;
        if (finished == DragMode.CREATE) {
            if (box.getWidth() < 4 || box.getHeight() < 4) {
                // A click instead of a drag: a standard-size box centred on
                // the click, so placing works with a single click too.
                double scale = rendered.pixelsPerPoint();
                double w = CLICK_BOX_W_PTS * scale;
                double h = CLICK_BOX_H_PTS * scale;
                box.setX(pressX - w / 2);
                box.setY(pressY - h / 2);
                box.setWidth(w);
                box.setHeight(h);
            }
            placing.set(false);
        }
        // Moving and resizing commit too: the box on screen is ALWAYS the
        // placement that will be signed.
        commit();
        scrollPane.setPannable(true);
        pageLayer.setCursor(Cursor.DEFAULT);
    }

    private void commit() {
        SignaturePlacement p = CoordinateMapper.toPlacement(currentPage, rendered,
                box.getX(), box.getY(), box.getWidth(), box.getHeight());
        placement.set(p);
        updateBox();
        status.set(overlapsExisting()
                ? "This signature overlaps an existing signature — consider another spot."
                : "Signature placed on page " + (p.pageIndex() + 1)
                        + ". Drag it to move, drag its corner to resize.");
    }

    private boolean isInsideBox(double px, double py) {
        return px >= box.getX() - 2 && px <= box.getX() + box.getWidth() + 2
                && py >= box.getY() - 2 && py <= box.getY() + box.getHeight() + 2;
    }

    private boolean nearBottomRightCorner(double px, double py) {
        double hit = Math.max(8, Math.min(14, box.getWidth() / 5));
        return Math.abs(px - (box.getX() + box.getWidth())) <= hit
                && Math.abs(py - (box.getY() + box.getHeight())) <= hit;
    }

    // --------------------------------------------------------------- helpers

    private void closeRenderer() {
        DocumentRenderer current = renderer;
        renderer = null;
        closeQuietly(current);
    }

    private static void closeQuietly(DocumentRenderer r) {
        if (r == null) {
            return;
        }
        try {
            r.close();
        } catch (Exception e) {
            log.debug("Closing the placement renderer: {}", e.getMessage());
        }
    }

    private static Button navButton(FontAwesomeSolid icon, String tooltip) {
        Button button = new Button("", new FontIcon(icon));
        button.getStyleClass().add("nav-btn");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private static void start(Task<?> task, String name) {
        Background.run(name, task);
    }
}
