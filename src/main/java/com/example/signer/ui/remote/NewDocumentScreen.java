package com.example.signer.ui.remote;

import com.example.signer.client.WorkflowApiClient;
import com.example.signer.server.api.ApiContracts.WorkflowView;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * "Sign a document on the server": choose a PDF, upload it, done.
 *
 * <p>There is nothing to decide beyond the file. The uploader is the only
 * signer, so there are no recipients to name, no order to pick and nothing
 * to send to anybody — the document comes back prepared for this account's
 * own signature, and the next screen is where that happens.
 *
 * <p>Renders into the host dialog's content container so the dialog's own
 * Back navigation keeps working.
 */
public final class NewDocumentScreen {

    private final WorkflowApiClient client;
    private final Stage owner;
    private final VBox content;
    private final Consumer<String> status;
    private final Runnable onDone;
    private final long maxUploadBytes;

    private Path document;

    public NewDocumentScreen(WorkflowApiClient client, Stage owner, VBox content,
                             Consumer<String> status, Runnable onDone,
                             long maxUploadBytes) {
        this.client = client;
        this.owner = owner;
        this.content = content;
        this.status = status;
        this.onDone = onDone;
        this.maxUploadBytes = maxUploadBytes;
    }

    /** Renders the screen into the host content container. */
    public void start() {
        status.accept("Choose the PDF you want to sign.");
        show();
    }

    private void show() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        Button pick = new Button(document == null ? "Choose PDF…"
                : "Replace document…", new FontIcon(FontAwesomeSolid.FOLDER_OPEN));
        pick.getStyleClass().add("btn-secondary");
        pick.setOnAction(e -> chooseDocument());

        VBox docInfo = new VBox(4);
        if (document == null) {
            VBox empty = new VBox(8);
            empty.getStyleClass().add("empty-state");
            empty.setAlignment(Pos.CENTER);
            Label icon = new Label();
            icon.setGraphic(new FontIcon(FontAwesomeSolid.FILE_PDF));
            icon.getStyleClass().add("empty-state-icon");
            Label title = new Label("No document selected yet");
            title.getStyleClass().add("empty-state-title");
            Label hint = new Label("Choose the PDF you want to sign.");
            hint.getStyleClass().add("empty-state-hint");
            hint.setWrapText(true);
            empty.getChildren().addAll(icon, title, hint);
            docInfo.getChildren().add(empty);
        } else {
            Label name = new Label(document.getFileName().toString());
            name.getStyleClass().add("doc-name");
            name.setWrapText(true);
            docInfo.getChildren().addAll(name, new Label(documentMeta()));
        }

        HBox actions = new HBox(10, pick);
        if (document != null) {
            Button remove = new Button("Remove", new FontIcon(FontAwesomeSolid.TRASH));
            remove.getStyleClass().add("btn-ghost");
            remove.setOnAction(e -> {
                document = null;
                status.accept("Document removed — choose a PDF to continue.");
                show();
            });
            actions.getChildren().add(remove);
        }
        actions.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(docInfo, actions);

        Button upload = new Button(
                document == null ? "Choose a document first" : "Upload and continue",
                new FontIcon(FontAwesomeSolid.ARROW_RIGHT));
        upload.getStyleClass().add("btn-primary");
        upload.setDisable(document == null);
        upload.setDefaultButton(true);
        upload.setOnAction(e -> upload());

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> onDone.run());

        content.getChildren().setAll(
                sectionTitle("Sign a document"),
                subtitle("The PDF is uploaded, prepared for your signature and"
                        + " checked on this computer again before you sign it."),
                card,
                new HBox(10, upload, cancel));
    }

    private void chooseDocument() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the document to sign");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        File file = chooser.showOpenDialog(owner);
        if (file != null) {
            document = file.toPath();
            String problem = uploadSizeProblem();
            if (problem != null) {
                // Rejected before any bytes travel: the limit is enforced
                // again by the server's multipart limits.
                document = null;
                status.accept(problem);
            } else {
                status.accept("Document selected: " + file.getName());
            }
            show();
        }
    }

    private void upload() {
        String problem = uploadSizeProblem();
        if (document == null || problem != null) {
            status.accept(problem == null ? "Choose the PDF you want to sign." : problem);
            return;
        }
        Path chosen = document;
        String documentName = chosen.getFileName().toString();
        status.accept("Uploading " + documentName + "…");
        Task<WorkflowView> task = new Task<>() {
            @Override
            protected WorkflowView call() throws Exception {
                return client.createWorkflow(documentName, Files.readAllBytes(chosen));
            }

            @Override
            protected void succeeded() {
                WorkflowView workflow = getValue();
                status.accept(workflow.awaitingYourCertificate()
                        ? "Uploaded. Register your signing key to prepare it."
                        : "Uploaded and ready to sign.");
                onDone.run();
            }

            @Override
            protected void failed() {
                status.accept("The document could not be uploaded: "
                        + friendly(getException()));
                show();
            }
        };
        Thread worker = new Thread(task, "upload-document");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * null when the chosen document is within the server's upload limit,
     * otherwise the message shown to the user.
     */
    private String uploadSizeProblem() {
        if (document == null) {
            return null;
        }
        long bytes;
        try {
            bytes = Files.size(document);
        } catch (Exception e) {
            return "The document could not be read: " + friendly(e);
        }
        if (bytes > maxUploadBytes) {
            return "This document is " + formatSize(bytes)
                    + ". The maximum upload size is " + formatSize(maxUploadBytes)
                    + " — choose a smaller PDF.";
        }
        return null;
    }

    /** "PDF · 231.4 KB" line shown under the document name. */
    private String documentMeta() {
        try {
            return "PDF · " + formatSize(Files.size(document));
        } catch (Exception e) {
            return "PDF · size unavailable";
        }
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private Label subtitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("page-subtitle");
        label.setWrapText(true);
        return label;
    }

    private static String friendly(Throwable t) {
        String message = t == null ? null : t.getMessage();
        if (message == null || message.isBlank()) {
            return "unexpected error";
        }
        int dot = message.indexOf(". ");
        return dot > 0 ? message.substring(0, dot + 1) : message;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
