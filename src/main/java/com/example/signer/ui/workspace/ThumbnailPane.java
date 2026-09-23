package com.example.signer.ui.workspace;

import com.example.signer.pdf.DocumentRenderer;
import com.example.signer.pdf.PdfRenderService;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Left-side page thumbnail navigation (§52): lazy background rendering, one
 * click-to-navigate thumbnail per page, current page highlighted. Thumbnails
 * render sequentially in a single background worker at thumbnail resolution
 * and never block the UI thread (§53/§35).
 */
final class ThumbnailPane extends ScrollPane {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailPane.class);
    private static final int THUMB_WIDTH_PX = 96;

    private final PdfRenderService renderService;
    private final VBox list = new VBox(8);
    private final List<ImageView> thumbs = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private IntConsumer pageClickListener = page -> { };
    private int currentPage = -1;
    private int renderGeneration;

    ThumbnailPane(PdfRenderService renderService) {
        this.renderService = renderService;
        setContent(list);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        getStyleClass().add("mws-thumbnail-pane");
        Label caption = new Label("PAGES");
        caption.getStyleClass().add("mws-thumbnail-caption");
        list.getChildren().add(caption);
    }

    /** Registers the callback invoked when a thumbnail is clicked. */
    void setPageClickListener(IntConsumer listener) {
        this.pageClickListener = listener;
    }

    /** Rebuilds the strip for the given document (renders lazily, in order). */
    void load(Path pdf, int pageCount) {
        renderGeneration++;
        final int generation = renderGeneration;
        list.getChildren().removeIf(node -> node instanceof ImageView
                || (node instanceof Label l && l.getStyleClass()
                        .contains("mws-thumbnail")));
        thumbs.clear();
        labels.clear();
        currentPage = -1;

        for (int i = 0; i < pageCount; i++) {
            Label placeholder = new Label("Page " + (i + 1));
            placeholder.getStyleClass().addAll("mws-thumbnail", "mws-thumbnail--pending");
            placeholder.setPrefSize(THUMB_WIDTH_PX, 0);
            final int index = i;
            placeholder.setOnMouseClicked(e -> pageClickListener.accept(index));
            labels.add(placeholder);
            list.getChildren().add(placeholder);
        }

        Thread worker = new Thread(() -> renderAll(pdf, generation, pageCount),
                "mws-thumbnails");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderAll(Path pdf, int generation, int pageCount) {
        try (DocumentRenderer renderer = renderService.openDocument(pdf)) {
            for (int i = 0; i < pageCount; i++) {
                if (generation != renderGeneration) {
                    return; // superseded by a newer document load
                }
                final int index = i;
                var image = renderer.renderThumbnail(index, THUMB_WIDTH_PX);
                WritableImage fx = SwingFXUtils.toFXImage(image, null);
                Platform.runLater(() -> installThumb(generation, index, fx));
            }
        } catch (Exception e) {
            log.warn("Thumbnail rendering failed: {}", e.getMessage());
        }
    }

    private void installThumb(int generation, int index, WritableImage fx) {
        if (generation != renderGeneration || index >= labels.size()) {
            return;
        }
        Label placeholder = labels.get(index);
        int position = list.getChildren().indexOf(placeholder);
        ImageView view = new ImageView(fx);
        view.setFitWidth(THUMB_WIDTH_PX);
        view.setPreserveRatio(true);
        view.getStyleClass().add("mws-thumbnail-image");
        view.setPickOnBounds(true);
        view.setOnMouseClicked(e -> pageClickListener.accept(index));
        if (position >= 0) {
            list.getChildren().set(position, view);
        }
        while (thumbs.size() <= index) {
            thumbs.add(null);
        }
        thumbs.set(index, view);
        highlight(currentPage);
    }

    /** Highlights the given page index (no-op when nothing is loaded yet). */
    void highlight(int pageIndex) {
        currentPage = pageIndex;
        for (int i = 0; i < thumbs.size(); i++) {
            ImageView view = thumbs.get(i);
            if (view == null) {
                continue;
            }
            view.getStyleClass().remove("mws-thumbnail-image--current");
            if (i == pageIndex) {
                view.getStyleClass().add("mws-thumbnail-image--current");
            }
        }
    }
}
