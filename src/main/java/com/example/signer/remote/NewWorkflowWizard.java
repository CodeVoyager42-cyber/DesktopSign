package com.example.signer.remote;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Hyperlink;
import com.example.signer.ui.support.Background;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Guided "New signing workflow" flow — replaces the old flat form so the
 * signer/invitation functionality is impossible to miss:
 * <ol>
 *   <li><b>Document</b> — pick the PDF (name, size, type; replace/remove),</li>
 *   <li><b>Signers</b> — "Who needs to sign?" cards with order chips, live
 *   email-vs-username badges, remove and reorder, sequential/flexible
 *   policy with a plain-language explanation,</li>
 *   <li><b>Review &amp; send</b> — a "Ready to send" summary and a single
 *   obvious "Send for signature" action,</li>
 *   <li><b>Success</b> — who was invited by email and what happens next.</li>
 * </ol>
 *
 * <p>Email-shaped signer slots are invited automatically by the server
 * ({@code WorkflowInvitationCoordinator}); username slots must already exist.
 * The wizard surfaces that distinction live instead of hiding it.
 */
public final class NewWorkflowWizard {

    /** Renders one signer card's editable state. */
    private static final class SignerRow {
        final TextField identifier = new TextField();
        final TextField fingerprint = new TextField();
        final Label badge = new Label();
        VBox card;
    }

    private final WorkflowApiClient client;
    private final TokenService tokenService;
    private final Stage owner;
    private final VBox content;
    private final Consumer<String> status;
    private final String signedInUser;
    private final Runnable onDone;
    private final Runnable onInviteAnother;
    /** Largest document the coordination server accepts (config-derived). */
    private final long maxUploadBytes;

    private Path document;
    private String policy = "SEQUENTIAL";
    private final List<SignerRow> rows = new ArrayList<>();

    public NewWorkflowWizard(WorkflowApiClient client, TokenService tokenService,
                             Stage owner, VBox content, Consumer<String> status,
                             String signedInUser, Runnable onDone,
                             Runnable onInviteAnother, long maxUploadBytes) {
        this.client = client;
        this.tokenService = tokenService;
        this.owner = owner;
        this.content = content;
        this.status = status;
        this.signedInUser = signedInUser;
        this.onDone = onDone;
        this.onInviteAnother = onInviteAnother;
        this.maxUploadBytes = maxUploadBytes;
    }

    /** Renders step 1 into the host content container. */
    public void start() {
        status.accept("New signing workflow — start with the document.");
        showDocumentStep();
    }

    /* ------------------------------------------------------------------ */
    /* step indicator                                                      */
    /* ------------------------------------------------------------------ */

    private HBox stepIndicator(int active) {
        HBox bar = new HBox(8);
        bar.getStyleClass().add("wizard-steps");
        String[] names = {"1 · Document", "2 · Signers", "3 · Review & send"};
        for (int i = 1; i <= names.length; i++) {
            Label step = new Label(names[i - 1]);
            step.getStyleClass().add("wizard-step");
            if (i < active) {
                step.getStyleClass().add("wizard-step--done");
            } else if (i == active) {
                step.getStyleClass().add("wizard-step--active");
            }
            bar.getChildren().add(step);
        }
        return bar;
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

    private Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /* ------------------------------------------------------------------ */
    /* step 1 — document                                                   */
    /* ------------------------------------------------------------------ */

    private void showDocumentStep() {
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
            Label hint = new Label("Choose the PDF that needs signatures.");
            hint.getStyleClass().add("empty-state-hint");
            hint.setWrapText(true);
            empty.getChildren().addAll(icon, title, hint);
            docInfo.getChildren().add(empty);
        } else {
            Label name = new Label(document.getFileName().toString());
            name.getStyleClass().add("doc-name");
            name.setWrapText(true);
            docInfo.getChildren().add(name);
            docInfo.getChildren().add(new Label(documentMeta()));
        }

        HBox actions = new HBox(10, pick);
        if (document != null) {
            Button remove = new Button("Remove",
                    new FontIcon(FontAwesomeSolid.TRASH));
            remove.getStyleClass().add("btn-ghost");
            remove.setOnAction(e -> {
                document = null;
                status.accept("Document removed — choose a PDF to continue.");
                showDocumentStep();
            });
            actions.getChildren().add(remove);
        }
        actions.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(docInfo, actions);

        Button next = new Button(
                document == null ? "Continue — choose a document"
                        : "Continue — add signers",
                new FontIcon(FontAwesomeSolid.ARROW_RIGHT));
        next.getStyleClass().add("btn-primary");
        next.setDisable(document == null);
        next.setDefaultButton(true);
        next.setOnAction(e -> showSignersStep());

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> onDone.run());

        content.getChildren().setAll(
                stepIndicator(1),
                sectionTitle("Start a signing workflow"),
                subtitle("The document travels through every signing step and"
                        + " is verified before each signature."),
                card,
                new HBox(10, next, cancel));
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
            showDocumentStep();
        }
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
                    + ". The maximum upload size is "
                    + formatSize(maxUploadBytes)
                    + " — choose a smaller PDF.";
        }
        return null;
    }

    /** "PDF · 231.4 KB" line shown under the document name. */
    private String documentMeta() {
        try {
            long bytes = Files.size(document);
            return "PDF · " + formatSize(bytes);
        } catch (Exception e) {
            return "PDF · size unavailable";
        }
    }

    /* ------------------------------------------------------------------ */
    /* step 2 — signers                                                    */
    /* ------------------------------------------------------------------ */

    private void showSignersStep() {
        VBox list = new VBox(10);
        list.getStyleClass().add("signer-list");
        for (SignerRow row : rows) {
            list.getChildren().add(row.card);
        }

        Button add = new Button("+ Add signer",
                new FontIcon(FontAwesomeSolid.USER_PLUS));
        add.getStyleClass().add("btn-add-signer");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setTooltip(new Tooltip(
                "Add another person who must sign. Email addresses are"
                        + " invited automatically when you send."));
        add.setOnAction(e -> {
            rows.add(newRow(null));
            showSignersStep();
        });

        VBox signerSection = new VBox(10);
        if (rows.isEmpty()) {
            VBox empty = new VBox(8);
            empty.getStyleClass().add("empty-state");
            empty.setAlignment(Pos.CENTER);
            Label icon = new Label();
            icon.setGraphic(new FontIcon(FontAwesomeSolid.USERS));
            icon.getStyleClass().add("empty-state-icon");
            Label title = new Label("No signers added yet");
            title.getStyleClass().add("empty-state-title");
            Label hint = new Label("Add the people who need to sign this document.");
            hint.getStyleClass().add("empty-state-hint");
            hint.setWrapText(true);
            empty.getChildren().addAll(icon, title, hint);
            signerSection.getChildren().addAll(empty, add);
        } else {
            signerSection.getChildren().addAll(list, add);
        }

        ComboBox<String> policyBox = new ComboBox<>();
        policyBox.getItems().addAll(
                "Sequential — one after another (1 → 2 → 3)",
                "Flexible — any pending signer can take the turn");
        policyBox.getSelectionModel().select(
                "FLEXIBLE".equals(policy) ? 1 : 0);
        policyBox.setMaxWidth(420);
        policyBox.valueProperty().addListener((o, was, value) -> {
            policy = value != null && value.startsWith("Flexible")
                    ? "FLEXIBLE" : "SEQUENTIAL";
            status.accept("FLEXIBLE".equals(policy)
                    ? "Flexible order: any pending signer may claim the turn."
                    : "Sequential order: signers act in the order shown.");
        });

        Button back = new Button("Back",
                new FontIcon(FontAwesomeSolid.ARROW_LEFT));
        back.getStyleClass().add("btn-ghost");
        back.setOnAction(e -> showDocumentStep());

        Button next = new Button("Continue — review & send",
                new FontIcon(FontAwesomeSolid.ARROW_RIGHT));
        next.getStyleClass().add("btn-primary");
        next.setDisable(rows.isEmpty());
        next.setDefaultButton(true);
        next.setOnAction(e -> showReviewStep());

        content.getChildren().setAll(
                stepIndicator(2),
                sectionTitle("Who needs to sign?"),
                subtitle("Type each signer's email address. They receive an"
                        + " invitation email when you send. (People who already"
                        + " have a SignFlow account can also be added by"
                        + " username.)"),
                signerSection,
                new VBox(4, new Label("Signing order"), policyBox,
                        subtitle("SEQUENTIAL".equals(policy)
                                ? "Sequential: signer 2 is invited to sign only"
                                + " after signer 1 has signed."
                                : "Flexible: the turn is open — any pending"
                                + " signer may claim it, one at a time.")),
                new HBox(10, back, next));
    }

    /** Builds one editable signer card with live email/username badge. */
    private SignerRow newRow(String preset) {
        SignerRow row = new SignerRow();
        int order = rows.size() + 1;

        row.identifier.setPromptText(
                "email address (invited by email) or username");
        row.identifier.setPrefColumnCount(30);
        if (preset != null) {
            row.identifier.setText(preset);
        }
        row.identifier.textProperty().addListener((o, was, is) ->
                updateBadge(row));

        row.fingerprint.setPromptText(
                "certificate fingerprint — or click Capture with the token plugged in");
        row.fingerprint.setPrefColumnCount(44);

        Button capture = new Button("Capture",
                new FontIcon(FontAwesomeSolid.ID_BADGE));
        capture.getStyleClass().add("btn-ghost");
        capture.setTooltip(new Tooltip(
                "Read the certificate of the USB token connected to THIS"
                        + " computer into this row (public material only — no PIN)"));
        capture.setOnAction(e -> captureCertificate(row));

        Button remove = new Button("", new FontIcon(FontAwesomeSolid.TRASH));
        remove.getStyleClass().add("btn-icon");
        remove.setTooltip(new Tooltip("Remove this signer"));
        remove.setOnAction(e -> {
            rows.remove(row);
            showSignersStep();
        });

        Button up = new Button("", new FontIcon(FontAwesomeSolid.CHEVRON_UP));
        up.getStyleClass().add("btn-icon");
        up.setTooltip(new Tooltip("Move this signer earlier in the order"));
        Button down = new Button("", new FontIcon(FontAwesomeSolid.CHEVRON_DOWN));
        down.getStyleClass().add("btn-icon");
        down.setTooltip(new Tooltip("Move this signer later in the order"));

        HBox header = new HBox(8, orderChip(order), row.identifier,
                row.badge, spacer(), up, down, remove);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row.identifier, Priority.ALWAYS);

        HBox sub = new HBox(8, new Label("Signing key"), row.fingerprint, capture);
        sub.getStyleClass().add("signer-sub");
        sub.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row.fingerprint, Priority.ALWAYS);

        // Advanced, optional: pre-assigning the signer's key. Hidden by
        // default — signers normally register their own key when they open
        // the invitation, so most senders never need this.
        Hyperlink advanced = new Hyperlink(
                "Advanced: set this signer's signing key now (optional)");
        boolean keyPreset = !row.fingerprint.getText().isBlank();
        sub.setVisible(keyPreset);
        sub.setManaged(keyPreset);
        advanced.setVisible(!keyPreset);
        advanced.setManaged(!keyPreset);
        advanced.setOnAction(e -> {
            sub.setVisible(true);
            sub.setManaged(true);
            advanced.setVisible(false);
            advanced.setManaged(false);
        });

        row.card = new VBox(6, header, advanced, sub);
        row.card.getStyleClass().add("signer-card");
        row.card.setPadding(new Insets(10, 12, 10, 12));
        updateBadge(row);
        return row;
    }

    private Label orderChip(int order) {
        Label chip = new Label(String.valueOf(order));
        chip.getStyleClass().add("signer-order");
        return chip;
    }

    /** Live badge: '@' → invited by email, otherwise an existing account. */
    private void updateBadge(SignerRow row) {
        String text = row.identifier.getText() == null
                ? "" : row.identifier.getText().trim();
        row.badge.getStyleClass().removeAll(
                "role-badge--email", "role-badge--username");
        if (text.isEmpty()) {
            row.badge.setText("");
            row.badge.setVisible(false);
            row.badge.setManaged(false);
        } else if (text.contains("@")) {
            row.badge.setText("invited by email");
            row.badge.getStyleClass().add("role-badge--email");
            row.badge.setVisible(true);
            row.badge.setManaged(true);
        } else {
            row.badge.setText("existing account");
            row.badge.getStyleClass().add("role-badge--username");
            row.badge.setVisible(true);
            row.badge.setManaged(true);
        }
    }

    /* ------------------------------------------------------------------ */
    /* step 3 — review & send                                              */
    /* ------------------------------------------------------------------ */

    private void showReviewStep() {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        card.getChildren().add(new Label("Document:"));
        Label doc = new Label(document.getFileName().toString());
        doc.getStyleClass().add("doc-name");
        doc.setWrapText(true);
        card.getChildren().add(doc);
        card.getChildren().add(new Label(documentMeta()));

        card.getChildren().addAll(new Label("Signers:"), signerSummary("✓"));

        card.getChildren().add(new Label("Signing order:"));
        Label order = new Label("FLEXIBLE".equals(policy)
                ? "Flexible — any pending signer may take the turn"
                : "Sequential — " + orderSummary());
        order.setWrapText(true);
        card.getChildren().add(order);

        Button back = new Button("Back",
                new FontIcon(FontAwesomeSolid.ARROW_LEFT));
        back.getStyleClass().add("btn-ghost");
        back.setOnAction(e -> showSignersStep());

        Button send = new Button("Send for signature",
                new FontIcon(FontAwesomeSolid.PAPER_PLANE));
        send.getStyleClass().add("btn-primary");
        send.setDefaultButton(true);
        send.setOnAction(e -> submit());

        content.getChildren().setAll(
                stepIndicator(3),
                sectionTitle("Ready to send"),
                subtitle("One last look — the invitation emails go out as soon"
                        + " as you send."),
                card,
                new HBox(10, back, send));
    }

    /** "1 · john@example.com → 2 · jane@example.com" for sequential. */
    private String orderSummary() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                text.append(" → ");
            }
            text.append(i + 1).append(" · ")
                    .append(rows.get(i).identifier.getText().trim());
        }
        return text.toString();
    }

    private VBox signerSummary(String marker) {
        VBox box = new VBox(4);
        for (int i = 0; i < rows.size(); i++) {
            String id = rows.get(i).identifier.getText().trim();
            Label line = new Label(marker + "  " + (i + 1) + ".  " + id
                    + (id.contains("@") ? "  —  invited by email"
                            : "  —  existing account"));
            line.setWrapText(true);
            box.getChildren().add(line);
        }
        return box;
    }

    private void submit() {
        List<String> identifiers = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        for (SignerRow row : rows) {
            identifiers.add(row.identifier.getText());
            fingerprints.add(row.fingerprint.getText());
        }
        String problem = validateSigners(identifiers, fingerprints);
        if (problem != null) {
            status.accept(problem);
            showSignersStep();
            return;
        }
        String sizeProblem = uploadSizeProblem();
        if (sizeProblem != null) {
            status.accept(sizeProblem);
            showDocumentStep();
            return;
        }
        byte[] pdf;
        try {
            pdf = Files.readAllBytes(document);
        } catch (Exception e) {
            status.accept("The document could not be read: "
                    + friendly(e));
            showDocumentStep();
            return;
        }
        List<CreateWorkflowRequest.SignerSpec> signers = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String id = identifiers.get(i).trim();
            String fingerprint = fingerprints.get(i);
            String hex = fingerprint == null ? ""
                    : fingerprint.replace(":", "").trim();
            signers.add(new CreateWorkflowRequest.SignerSpec(i + 1, id,
                    hex.isEmpty() ? null : hex));
        }

        content.getChildren().setAll(stepIndicator(3),
                sectionTitle("Ready to send"),
                subtitle("Sending — creating the workflow and delivering the"
                        + " invitation emails…"));
        run(new Task<CreateWorkflowResponse>() {
            private CreateWorkflowResponse created;

            @Override
            protected CreateWorkflowResponse call() {
                created = client.createWorkflow(
                        document.getFileName().toString(), policy, signers, pdf);
                return created;
            }

            @Override
            protected void succeeded() {
                showSuccess(getValue());
            }

            @Override
            protected void failed() {
                status.accept("The workflow could not be created: "
                        + friendly(getException()));
                showReviewStep();
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* success                                                             */
    /* ------------------------------------------------------------------ */

    private void showSuccess(CreateWorkflowResponse created) {
        WorkflowView workflow = created.workflow();
        List<InvitationLinkView> sentLinks = created.invitations() == null
                ? List.of() : created.invitations();
        long invited = rows.stream().map(r -> r.identifier.getText().trim())
                .filter(id -> id.contains("@")).count();

        Label title = new Label("Invitations sent successfully");
        title.getStyleClass().add("success-title");

        VBox summary = signerSummary("✓");
        VBox card = new VBox(10, title, subtitle("Workflow "
                + workflow.workflowId() + " is ready."), summary);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        String hint = invited > 0
                ? invited + " signer" + (invited == 1 ? " has" : "s have")
                        + " been invited by email. Each signer opens the link,"
                        + " reviews the document and signs on their own machine."
                : "All signers are existing accounts — they will find the"
                        + " workflow under their signing tasks.";

        Button invite = new Button("Invite another signer…",
                new FontIcon(FontAwesomeSolid.ENVELOPE_OPEN));
        invite.getStyleClass().add("btn-secondary");
        invite.setOnAction(e -> onInviteAnother.run());

        Button again = new Button("Create another workflow",
                new FontIcon(FontAwesomeSolid.PLUS));
        again.getStyleClass().add("btn-ghost");
        again.setOnAction(e -> start());

        Button done = new Button("View signing tasks",
                new FontIcon(FontAwesomeSolid.CHECK));
        done.getStyleClass().add("btn-primary");
        done.setDefaultButton(true);
        done.setOnAction(e -> onDone.run());

        content.getChildren().setAll(
                sectionTitle("Workflow sent"),
                card,
                new Label(hint),
                sentLinks.isEmpty() ? new Region() : sentLinksCard(sentLinks),
                new HBox(10, done, invite, again));
        status.accept("Workflow " + workflow.workflowId()
                + " created — invitations sent.");
    }

    /**
     * Shows the exact links that just went out, with a copy action per
     * recipient. The server returns them only here — it keeps token hashes,
     * not tokens — so this is the sender's one chance to see or share them.
     */
    private VBox sentLinksCard(List<InvitationLinkView> links) {
        Label title = new Label(links.size() == 1
                ? "Link in the email" : "Links in the emails");
        title.getStyleClass().add("card-title");

        Label note = new Label("This is exactly what your signers received."
                + " Keep it private — anyone who opens the link can sign.");
        note.setWrapText(true);
        note.getStyleClass().add("page-subtitle");

        VBox rows = new VBox(8);
        for (InvitationLinkView link : links) {
            Label email = new Label(link.email());
            email.getStyleClass().add("timeline-name");

            Label url = new Label(shortenLink(link.invitationUrl()));
            url.getStyleClass().addAll("page-subtitle", "mono-text");
            url.setTooltip(new Tooltip(link.invitationUrl()));

            Button copy = new Button("Copy link",
                    new FontIcon(FontAwesomeSolid.COPY));
            copy.getStyleClass().add("btn-ghost");
            copy.setOnAction(e -> {
                copyToClipboard(link.invitationUrl());
                copy.setText("Copied");
            });

            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox head = new HBox(10, email, gap, copy);
            head.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().addAll(head, url);
        }

        VBox card = new VBox(10, title, note, rows);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));
        return card;
    }

    /** Middle of a long link: the base URL and token tail stay visible. */
    static String shortenLink(String url) {
        if (url == null || url.length() <= 72) {
            return String.valueOf(url);
        }
        return url.substring(0, 40) + "…" + url.substring(url.length() - 24);
    }

    static void copyToClipboard(String text) {
        StringSelection selection =
                new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(selection, selection);
    }

    /* ------------------------------------------------------------------ */
    /* validation (pure — unit-tested without JavaFX)                      */
    /* ------------------------------------------------------------------ */

    /**
     * Client-side mirror of the server rules. Returns {@code null} when the
     * rows can be sent, otherwise a user-facing error naming the row.
     */
    static String validateSigners(List<String> identifiers,
                                  List<String> fingerprints) {
        if (identifiers == null || identifiers.isEmpty()) {
            return "Add at least one signer before sending.";
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < identifiers.size(); i++) {
            int n = i + 1;
            String id = identifiers.get(i) == null
                    ? "" : identifiers.get(i).trim();
            if (id.isEmpty()) {
                return "Signer " + n + ": enter an email address or username.";
            }
            if (id.contains("@") && !id.matches(".+@.+\\..+")) {
                return "Signer " + n + ": '" + id
                        + "' is not a valid email address.";
            }
            if (!seen.add(id.toLowerCase(Locale.ROOT))) {
                return "Two signer rows contain '" + id
                        + "' — each signer must be unique.";
            }
            String hex = fingerprints.get(i) == null ? ""
                    : fingerprints.get(i).replace(":", "").trim();
            if (!hex.isEmpty() && !hex.matches("[0-9a-fA-F]{64}")) {
                return "Signer " + n + ": the certificate fingerprint must be"
                        + " 64 hex characters — use Capture, or leave the field"
                        + " empty to let the signer bind their own certificate.";
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Reads the certificate of the token connected to THIS computer into one
     * row. Public material only — the PIN is never involved.
     */
    private void captureCertificate(SignerRow row) {
        run(new Task<List<SigningCertificate>>() {
            private List<SigningCertificate> certs;

            @Override
            protected List<SigningCertificate> call() {
                tokenService.disconnect();
                tokenService.detect();
                certs = tokenService.getCertificates();
                return certs;
            }

            @Override
            protected void succeeded() {
                if (certs == null || certs.isEmpty()) {
                    status.accept("No signing certificate found on the token.");
                    return;
                }
                if (certs.size() == 1) {
                    row.fingerprint.setText(certs.get(0).getSha256Fingerprint());
                    status.accept("Captured: "
                            + shortSubject(certs.get(0).getSubject()));
                    return;
                }
                ChoiceDialog<SigningCertificate> picker =
                        new ChoiceDialog<>(certs.get(0), certs);
                picker.setTitle("Choose a certificate");
                picker.setHeaderText("Which certificate belongs to this signer?");
                picker.showAndWait().ifPresent(cert -> {
                    row.fingerprint.setText(cert.getSha256Fingerprint());
                    status.accept("Captured: " + shortSubject(cert.getSubject()));
                });
            }

            @Override
            protected void failed() {
                status.accept("The signing token could not be read: "
                        + friendly(getException()));
            }
        });
    }

    private void run(Task<?> task) {
        Background.run("new-workflow-wizard", task);
    }

    private static String shortSubject(String subject) {
        Matcher matcher = Pattern
                .compile("(?:^|,)CN=([^,]+)").matcher(subject == null ? "" : subject);
        return matcher.find() ? matcher.group(1) : String.valueOf(subject);
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
        return String.format(Locale.ROOT, "%.1f MB",
                bytes / (1024.0 * 1024.0));
    }
}
