package com.example.signer.ui.remote;

import com.example.signer.client.RemoteSessionCache;
import com.example.signer.client.SigningTaskModels;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.server.api.ApiContracts;
import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.kit.SignFlowUi;
import com.example.signer.ui.support.UiSupport;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import com.example.signer.ui.support.Background;
import com.example.signer.ui.support.ErrorMessages;
import com.example.signer.config.LoginProperties;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.workflow.WorkflowSigningService;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.List;
import java.util.function.Consumer;

/**
 * The REMOTE signing experience: choose a workflow user, see "My Signing
 * Tasks", review the task (document, revision, signer progress), unlock the
 * LOCAL token with the PIN, sign through the existing domain, and see the
 * server's acceptance. Runs network and signing work on background tasks —
 * the FX thread is never blocked.
 *
 * <p>Security: the PIN field is cleared after use and its value is passed
 * only to the local {@link TokenService}; it is never sent anywhere.
 */
public final class RemoteWorkflowDialog {

    private final LoginProperties login;
    private final String baseUrl;
    private final TokenService tokenService;
    private final WorkflowSigningService signingService;
    /** Largest document the coordination server accepts (config-derived). */
    private final long maxUploadBytes;

    private Stage dialog;
    /** Column of the task page (the owner's invitations card is added to it). */
    private VBox taskColumn;
    private BorderPane root;
    private Button backButton;
    private TextField userField;
    private PasswordField passwordField;
    private VBox content;
    private Label statusLabel;
    private ProgressIndicator spinner;
    private WorkflowApiClient client;
    private String currentWorkflowId;

    // connectivity indicator (sign-in screen)
    private Label connDot;
    private Label connLabel;

    // create-workflow screen state
    private String signedInUser;

    // internal navigation: the screens visited inside this dialog (newest
    // first). The Back button pops to the previously visited screen instead
    // of leaving the dialog; only the first screen closes it.
    private final Deque<Runnable> screenHistory = new ArrayDeque<>();
    /** Builder of the screen currently displayed. */
    private Runnable currentScreen;
    /** Guards re-entrant recording while navigating back / refreshing. */
    private boolean navigatingBack;

    /** Session handed in via {@link #showAndWait(Window, WorkflowApiClient, String)}. */
    private WorkflowApiClient preClient;
    /** Username of the pre-authenticated session. */
    private String preUsername;
    /** Workflow to open automatically when landing on the workspace. */
    private String focusWorkflowIdParam;

    /** The signing screens (workspace → confirm → progress → result). */
    private final RemoteSigningJourney journey;

    /**
     * All collaborators are handed in (the application resolves them once
     * from Spring); the dialog never creates domain services itself.
     */
    public RemoteWorkflowDialog(LoginProperties login, String baseUrl,
                                TokenService tokenService,
                                WorkflowSigningService signingService,
                                PdfRenderService renderService,
                                long maxUploadBytes) {
        this.login = login;
        this.baseUrl = baseUrl;
        this.tokenService = tokenService;
        this.signingService = signingService;
        this.maxUploadBytes = maxUploadBytes;
        this.journey = new RemoteSigningJourney(new JourneyHost(), tokenService,
                signingService, renderService);
    }

    /** The dialog's side of {@link RemoteSigningJourney.Host}. */
    private final class JourneyHost implements RemoteSigningJourney.Host {
        @Override
        public Stage window() {
            return dialog;
        }

        @Override
        public VBox content() {
            return content;
        }

        @Override
        public Label status() {
            return statusLabel;
        }

        @Override
        public Button backButton() {
            return backButton;
        }

        @Override
        public WorkflowApiClient client() {
            return client;
        }

        @Override
        public boolean taskSessionMode() {
            return taskSessionMode;
        }

        @Override
        public String signingAsLabel() {
            return RemoteWorkflowDialog.this.signingAsLabel();
        }

        @Override
        public String signedInUser() {
            return signedInUser;
        }

        @Override
        public void navigateTo(Runnable screen) {
            RemoteWorkflowDialog.this.navigateTo(screen);
        }

        @Override
        public void showInPlace(Runnable screen) {
            currentScreen = screen;
            navigatingBack = true;
            try {
                screen.run();
            } finally {
                navigatingBack = false;
            }
        }

        @Override
        public void goBack() {
            RemoteWorkflowDialog.this.goBack();
        }

        @Override
        public void showTasks() {
            RemoteWorkflowDialog.this.showTasks();
        }

        @Override
        public void clearHistory() {
            screenHistory.clear();
            currentScreen = null;
        }

        @Override
        public void runInBackground(Task<?> task) {
            RemoteWorkflowDialog.this.runInBackground(task);
        }
    }

    /**
     * Shows the dialog pre-authenticated (invitation flow, brief §43): the
     * caller supplies a client built from the accepted invitation's token
     * pair; the dialog skips sign-in and lands directly on Signing Tasks.
     */
    public void showAndWait(Window owner,
                            WorkflowApiClient preAuthenticated,
                            String username) {
        showAndWait(owner, preAuthenticated, username, null);
    }

    /**
     * Pre-authenticated entry with an optional workflow to focus (brief
     * §20): invitation acceptance lands directly on that signing request
     * instead of making the user search for it.
     */
    public void showAndWait(Window owner,
                            WorkflowApiClient preAuthenticated,
                            String username,
                            String focusWorkflowId) {
        preClient = preAuthenticated;
        preUsername = username;
        focusWorkflowIdParam = focusWorkflowId;
        showAndWait(owner);
        preClient = null;
        preUsername = null;
        focusWorkflowIdParam = null;
    }

    /* ------------------------------------------------------------------
     * single-task session (opened from a website launch link)
     * ------------------------------------------------------------------ */

    /** True when this dialog runs a single-task session from a launch link. */
    private boolean taskSessionMode;
    private String taskWorkflowId;
    private String taskDocumentName;

    /**
     * Opens the dialog for a session scoped to ONE signing task: no sign-in
     * screen, no document list — straight to the document the launch link
     * was issued for.
     *
     * @param signerId the slot's signer id (as listed in the task)
     */
    public void showTaskSessionAndWait(Window owner, WorkflowApiClient taskClient,
                                       String signerId, String workflowId,
                                       String documentName) {
        taskSessionMode = true;
        taskWorkflowId = workflowId;
        taskDocumentName = documentName;
        try {
            showAndWait(owner, taskClient, signerId, workflowId);
        } finally {
            taskSessionMode = false;
            taskWorkflowId = null;
        }
    }

    /** Opens the task — straight into the workspace when it can be signed. */
    private void openLaunchedTask() {
        content.getChildren().setAll(RemoteScreens.centered(RemoteScreens.loadingCard("Opening your signing task…")));
        runInBackground(new Task<TaskView>() {
            @Override
            protected TaskView call() {
                return client.task(taskWorkflowId);
            }

            @Override
            protected void succeeded() {
                TaskView task = getValue();
                TaskView.StepView mine = task.signers().stream()
                        .filter(s -> signedInUser.equals(s.userId()))
                        .findFirst().orElse(null);
                boolean canSign = !"DRAFT".equals(task.status()) && mine != null
                        && !mine.signed() && mine.yourTurn();
                if (canSign) {
                    journey.showReview(task);
                } else {
                    showTask(taskWorkflowId);
                }
            }

            @Override
            protected void failed() {
                journey.showFailure(getException(), null, RemoteWorkflowDialog.this::openLaunchedTask);
            }
        });
    }

    /** "Signing as": the signed-in account. */
    private String signingAsLabel() {
        return signedInUser;
    }

    /** Lands on the document list after sign-in. */
    private void afterSignedIn() {
        showTasks();
    }

    /** Shows the dialog; returns when it is closed. */
    public void showAndWait(Window owner) {
        dialog = new Stage(StageStyle.DECORATED);
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("SignFlow — Signing Tasks");
        dialog.setMinWidth(720);
        dialog.setMinHeight(520);

        spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);
        statusLabel = new Label("Sign in with your SignFlow account.");
        statusLabel.getStyleClass().add("page-subtitle");
        statusLabel.setWrapText(true);

        content = new VBox(14);
        content.setPadding(new Insets(20));

        backButton = new Button("Back", new FontIcon(FontAwesomeSolid.ARROW_LEFT));
        backButton.getStyleClass().add("btn-ghost");
        backButton.setCancelButton(true);
        backButton.setTooltip(new Tooltip("Return to the previously visited screen (Esc)"));
        backButton.setOnAction(e -> goBack());

        HBox top = new HBox(12, backButton, statusLabel, spacer(), spinner);
        top.setAlignment(Pos.CENTER_LEFT);

        root = new BorderPane();
        root.setTop(top);
        root.setCenter(content);
        BorderPane.setMargin(top, new Insets(12, 16, 0, 16));

        Scene scene = new Scene(root, 900, 640);
        ThemeManager.instance().attach(scene);
        dialog.setScene(scene);
        // Closing mid-signature would abandon a signature half-way through
        // (token unlocked, upload in flight): wait for the outcome instead.
        dialog.setOnCloseRequest(e -> {
            if (journey.isSigning()) {
                e.consume();
            }
        });
        dialog.setOnHidden(e -> journey.leaveSigning());
        UiSupport.applyAppIcon(dialog);
        if (preClient != null && preUsername != null) {
            client = preClient;
            signedInUser = preUsername;
            focusWorkflowId = focusWorkflowIdParam;
            if (taskSessionMode) {
                dialog.setTitle("SignFlow — Sign "
                        + SigningTaskModels.readableTitle(taskDocumentName));
                statusLabel.setText("");
                openLaunchedTask();
            } else {
                afterSignedIn();
            }
        } else {
            showSignIn();
        }
        dialog.showAndWait();
    }

    /* ------------------------------------------------------------------
     * 1. sign-in as a workflow user
     * ------------------------------------------------------------------ */

    private void showSignIn() {
        navigateTo(this::showSignIn);
        statusLabel.setText("Sign in with your SignFlow account.");

        // ---- connectivity indicator (probes the server immediately) ----
        connDot = new Label();
        connDot.getStyleClass().add("conn-dot");
        connLabel = new Label("Checking server…");
        connLabel.getStyleClass().add("conn-label");
        HBox connRow = new HBox(8, connDot, connLabel);
        connRow.setAlignment(Pos.CENTER_LEFT);
        runInBackground(new Task<Void>() {
            @Override
            protected Void call() {
                // Public health check (Phase B): no fake login, nothing counted
                // against the sign-in limits.
                WorkflowApiClient.health(baseUrl);
                return null;
            }

            @Override
            protected void failed() {
                if (isConnectivityFailure(getException())) {
                    setConn(false, "Server not reachable at " + baseUrl
                            + " — start the coordination server first.");
                } else {
                    setConn(true, "Server online at " + baseUrl);
                }
            }

            @Override
            protected void succeeded() {
                setConn(true, "Server online at " + baseUrl);
            }
        });

        userField = new TextField();
        userField.setPromptText("your username or email address");
        passwordField = new PasswordField();
        passwordField.setPromptText("your password");
        Button signIn = new Button("Sign in", new FontIcon(FontAwesomeSolid.SIGN_IN_ALT));
        signIn.getStyleClass().add("btn-primary");
        signIn.setDefaultButton(true);
        signIn.setOnAction(e -> runInBackground(new Task<Void>() {
            @Override
            protected Void call() {
                signedInUser = userField.getText().trim();
                // JWT login; the password char[] is cleared inside login().
                client = WorkflowApiClient.login(baseUrl, signedInUser,
                        passwordField.getText().toCharArray());
                passwordField.clear();
                client.myTasks(); // probe credentials
                return null;
            }

            @Override
            protected void succeeded() {
                // Remember this session so an emailed invitation opened later
                // in the same app run can skip the login (brief §6 Scenario 1).
                RemoteSessionCache.store(signedInUser, client);
                afterSignedIn();
            }

            @Override
            protected void failed() {
                showError(getException());
            }
        }));

        content.getChildren().setAll(
                sectionTitle("Sign in"),
                connRow,
                new Label("Sign in to send documents for signature and to"
                        + " see the documents waiting for you."),
                labeled("Username or email", userField),
                labeled("Password", passwordField),
                new HBox(10, signIn),
                noAccountHint());
    }

    private static Label noAccountHint() {
        Label hint = new Label("No account yet? If someone asked you to sign,"
                + " open the link in their invitation email — or go Back and"
                + " choose \"Open an invitation\".");
        hint.getStyleClass().add("page-subtitle");
        hint.setWrapText(true);
        return hint;
    }

    /* ------------------------------------------------------------------
     * 2. signing tasks workspace (redesigned My Signing Tasks)
     * ------------------------------------------------------------------ */

    /** The workspace view, created lazily per signed-in user. */
    private SigningTasksView tasksView;
    /** Workflow to open automatically after the next workspace render. */
    private String focusWorkflowId;

    private void showTasks() {
        if (taskSessionMode) {
            // A single-task session only reaches its own task (the server
            // refuses the document list): its "home" is that task.
            showTask(taskWorkflowId);
            return;
        }
        navigateTo(this::showTasks);
        journey.leaveSigning();
        if (tasksView == null) {
            tasksView = new SigningTasksView(client, signedInUser,
                    new SigningTasksView.Host() {
                        @Override
                        public void openTask(String workflowId) {
                            focusWorkflowId = workflowId;
                            showTask(workflowId);
                        }

                        @Override
                        public void openDocument(String workflowId) {
                            journey.openCurrentDocument(workflowId);
                        }

                        @Override
                        public void newWorkflow() {
                            showCreateWorkflow();
                        }

                        @Override
                        public void signOut() {
                            signOutToSignIn();
                        }

                        @Override
                        public void showDetails(
                                ApiContracts
                                        .TaskItemView item) {
                            showTaskDetailsDialog(item);
                        }
                    });
        }
        renderTasksWorkspace();
        final String focus = focusWorkflowId;
        tasksView.load(() -> {
            if (focus != null) {
                // Invitation entry (brief §20): return to the exact signing
                // request instead of making the user search for it.
                focusWorkflowId = null;
                showTask(focus);
            }
        });
    }

    /** Swaps the dialog content to the workspace view. */
    private void renderTasksWorkspace() {
        content.setPadding(Insets.EMPTY);
        journey.ensureWorkspaceSize();
        statusLabel.setText("");
        spinner.setVisible(false);
        content.getChildren().setAll(tasksView.getRoot());
        VBox.setVgrow(tasksView.getRoot(), Priority.ALWAYS);
        Platform.runLater(
                () -> tasksView.getRoot().requestFocus());
    }

    /** Sign out: end the session and return to the sign-in screen. */
    private void signOutToSignIn() {
        RemoteSessionCache.clear();
        if (client != null) {
            client.close();
            client = null;
        }
        signedInUser = null;
        tasksView = null;
        showSignIn();
    }

    /**
     * Technical details dialog (support view): the workflow id, revision
     * and integrity hash live here, out of the primary UI (brief §4/§27).
     */
    private void showTaskDetailsDialog(
            ApiContracts.TaskItemView item) {
        Dialog<ButtonType> detailsDialog =
                new Dialog<>();
        detailsDialog.setTitle("Document details");
        detailsDialog.setHeaderText("Technical reference — for support");
        detailsDialog.initOwner(dialog);
        detailsDialog.getDialogPane().getButtonTypes()
                .add(ButtonType.CLOSE);

        Insets pad = new Insets(4, 0, 4, 0);
        Label idValue =
                new Label(item.workflowId());
        idValue.getStyleClass().add("mono-text");
        idValue.setTooltip(new Tooltip("Click to copy"));
        Button copy = new Button("Copy",
                new FontIcon(FontAwesomeSolid.COPY));
        copy.getStyleClass().addAll("btn-ghost", "btn-sm");
        copy.setTooltip(new Tooltip("Copy the reference for support"));
        copy.setOnAction(e -> {
            StringSelection selection =
                    new StringSelection(item.workflowId());
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(selection, selection);
            copy.setText("Copied");
        });

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        grid.setPadding(pad);
        int row = 0;
        grid.add(new Label("Document"), 0, row);
        grid.add(new Label(item.documentName()), 1, row++);
        grid.add(new Label("Sent by"), 0, row);
        grid.add(new Label(
                item.senderDisplayName() == null
                        ? String.valueOf(item.senderId())
                        : item.senderDisplayName()), 1, row++);
        grid.add(new Label("Status"), 0, row);
        grid.add(new Label(item.status()), 1, row++);
        grid.add(new Label("Reference"), 0, row);
        grid.add(idValue, 1, row);
        grid.add(copy, 2, row);
        detailsDialog.getDialogPane().setContent(grid);
        detailsDialog.showAndWait();
    }

    /* ------------------------------------------------------------------
     * 3. document detail: state + pre-sign review
     * ------------------------------------------------------------------ */

    private void showTask(String workflowId) {
        navigateTo(() -> showTask(workflowId));
        journey.leaveSigning();
        currentWorkflowId = workflowId;
        runInBackground(new Task<TaskView>() {
            private TaskView task;

            @Override
            protected TaskView call() {
                task = client.task(workflowId);
                return task;
            }

            @Override
            protected void succeeded() {
                // Human header: document title + state; the workflow id stays
                // in the Details panel only (§4).
                statusLabel.setText("");
                TaskView.StepView mine = task.signers().isEmpty() ? null
                        : task.signers().get(0);
                boolean draft = "DRAFT".equals(task.status());
                boolean needsKey = draft && mine != null && !mine.bound() && !mine.signed();
                boolean signed = mine != null && mine.signed();
                boolean canSign = !draft && mine != null && !mine.signed()
                        && mine.yourTurn();

                // ---- header: document + one status chip --------------------
                FontIcon pdfIcon = new FontIcon(FontAwesomeSolid.FILE_PDF);
                pdfIcon.getStyleClass().add("sf-doc-icon");
                Label name = new Label(SigningTaskModels.readableTitle(task.documentName()));
                name.getStyleClass().add("sf-doc-name");
                name.setWrapText(true);
                String chipText;
                SignFlowUi.Tone chipTone;
                if (signed) {
                    chipText = "Signed";
                    chipTone = SignFlowUi.Tone.OK;
                } else if (needsKey) {
                    chipText = "Set up needed";
                    chipTone = SignFlowUi.Tone.WARN;
                } else if (canSign) {
                    chipText = "Ready to sign";
                    chipTone = SignFlowUi.Tone.INFO;
                } else {
                    chipText = "Being prepared";
                    chipTone = SignFlowUi.Tone.WARN;
                }
                Label chip = SignFlowUi.chip(chipText, chipTone);
                VBox titleBox = new VBox(6, name, chip);
                HBox header = new HBox(14, pdfIcon, titleBox);
                header.setAlignment(Pos.TOP_LEFT);

                // ---- the document, as the list already described it --------
                SigningTaskModels.SigningTask summary = taskSessionMode || tasksView == null
                        ? null : tasksView.summaryOf(workflowId);
                VBox requestBox = new VBox(10);
                if (summary != null) {
                    requestBox.getChildren().add(SignFlowUi.details(
                            "Status", summary.explanation(),
                            summary.dateKind(), summary.dateText()));
                }

                // ---- what happens next + the one action --------------------
                VBox card;
                if (needsKey) {
                    Button bind = SignFlowUi.primary(
                            "Register my signing key", FontAwesomeSolid.ID_BADGE);
                    bind.setMaxWidth(Region.USE_PREF_SIZE);
                    bind.setOnAction(e -> captureAndBind(workflowId));
                    card = SignFlowUi.card(header,
                            SignFlowUi.note(FontAwesomeSolid.ID_CARD,
                                    SignFlowUi.Tone.WARN,
                                    "One-time setup: plug in your smart card and register"
                                            + " your signing key. The document is prepared"
                                            + " as soon as you have."),
                            bind);
                } else {
                    String nextText = canSign
                            ? "Open the document, place your signature where you want it,"
                                    + " then sign with your smart card. The document is"
                                    + " checked on this computer before you sign."
                            : signed
                            ? "This document is signed. You can open it from here."
                            : "The document is being prepared. Refresh in a moment.";
                    HBox nextNote = SignFlowUi.note(
                            canSign ? FontAwesomeSolid.PEN_NIB
                                    : signed ? FontAwesomeSolid.CHECK_CIRCLE
                                    : FontAwesomeSolid.HOURGLASS_HALF,
                            canSign ? SignFlowUi.Tone.INFO
                                    : signed ? SignFlowUi.Tone.OK : SignFlowUi.Tone.WARN,
                            nextText);
                    card = SignFlowUi.card(header, nextNote);
                    if (canSign) {
                        Button signButton = SignFlowUi.primary(
                                "Review & Sign", FontAwesomeSolid.ARROW_RIGHT);
                        signButton.setMaxWidth(Region.USE_PREF_SIZE);
                        signButton.setOnAction(e -> journey.showReview(task));
                        card.getChildren().add(signButton);
                    }
                }
                Button refresh = SignFlowUi.secondary("Refresh");
                refresh.setGraphic(new FontIcon(FontAwesomeSolid.SYNC));
                refresh.setOnAction(e -> showTaskInPlace(workflowId));
                HBox footer = new HBox(refresh);
                footer.setAlignment(Pos.CENTER_RIGHT);
                card.getChildren().add(footer);
                if (!requestBox.getChildren().isEmpty()) {
                    card.getChildren().add(1, requestBox);
                }
                taskColumn = new VBox(12, card);
                if (!taskSessionMode) {
                    Hyperlink backToTasks = new Hyperlink("← Back to my documents");
                    backToTasks.setOnAction(e -> showTasks());
                    taskColumn.getChildren().add(0, backToTasks);
                }
                taskColumn.setMaxWidth(720);
                card.setMaxWidth(720);
                content.getChildren().setAll(RemoteScreens.centered(taskColumn));
            }

            @Override
            protected void failed() {
                showError(getException());
            }
        });
    }

    /* ------------------------------------------------------------------
     * 3b. upload a document to sign
     * ------------------------------------------------------------------ */

    /**
     * The upload screen: pick a PDF and send it to the server, which
     * prepares it for this account to sign. Renders into this dialog's
     * content area so Back navigation keeps working.
     */
    private void showCreateWorkflow() {
        navigateTo(this::showCreateWorkflow);
        new NewDocumentScreen(client, dialog, content, statusLabel::setText,
                this::showTasks, maxUploadBytes).start();
    }

    /* ------------------------------------------------------------------
     * connectivity helpers
     * ------------------------------------------------------------------ */

    /**
     * Distinguishes "cannot reach the server" from any other failure so the
     * indicator can tell the user exactly what to do.
     */
    private static boolean isConnectivityFailure(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof ConnectException
                    || cause instanceof UnresolvedAddressException
                    || cause instanceof UnknownHostException
                    || cause instanceof HttpTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void setConn(boolean online, String message) {
        connDot.getStyleClass().removeAll("conn-dot--online", "conn-dot--offline");
        connDot.getStyleClass().add(online ? "conn-dot--online" : "conn-dot--offline");
        connLabel.getStyleClass().removeAll("conn-label--online", "conn-label--offline");
        connLabel.getStyleClass().add(online ? "conn-label--online" : "conn-label--offline");
        connLabel.setText(message);
    }

    /** Short CN for status messages. */
    private static String shortSubject(String subject) {
        Matcher matcher = Pattern
                .compile("(?:^|,)CN=([^,]+)").matcher(subject == null ? "" : subject);
        return matcher.find() ? matcher.group(1) : String.valueOf(subject);
    }

    /* ------------------------------------------------------------------
     * 3c. certificate binding (enrollment on the signer's machine)
     * ------------------------------------------------------------------ */

    /**
     * Captures the connected token's certificate fingerprint and binds it to
     * the signed-in user's own unbound slot (§14). Public material only — the
     * PIN is not involved and the private key never leaves the token.
     */
    private void captureAndBind(String workflowId) {
        runInBackground(new Task<List<SigningCertificate>>() {
            private List<SigningCertificate> certs;

            @Override
            protected List<SigningCertificate> call() {
                tokenService.disconnect();
                tokenService.detect();
                // Task.call()'s result is delivered via getValue(); the
                // succeeded() callback reads this field, so assign it here.
                certs = tokenService.getCertificates();
                return certs;
            }

            @Override
            protected void succeeded() {
                if (certs == null || certs.isEmpty()) {
                    showError(new SignerException(ErrorCode.CERTIFICATE_NOT_FOUND,
                            "No signing certificate found on the connected token."));
                    return;
                }
                if (certs.size() == 1) {
                    bindCertificate(workflowId, certs.get(0).getSha256Fingerprint());
                    return;
                }
                ChoiceDialog<SigningCertificate> picker =
                        new ChoiceDialog<>(certs.get(0), certs);
                picker.setTitle("Choose a certificate");
                picker.setHeaderText("Which certificate is yours, "
                        + signedInUser + "?");
                picker.showAndWait().ifPresent(cert ->
                        bindCertificate(workflowId, cert.getSha256Fingerprint()));
            }

            @Override
            protected void failed() {
                showError(getException());
            }
        });
    }

    private void bindCertificate(String workflowId, String fingerprint) {
        runInBackground(new Task<WorkflowView>() {
            private WorkflowView bound;

            @Override
            protected WorkflowView call() {
                bound = client.bindMyCertificate(workflowId, fingerprint);
                return bound;
            }

            @Override
            protected void succeeded() {
                statusLabel.setText(bound.awaitingYourCertificate()
                        ? "Certificate bound — waiting for the other signer(s)."
                        : "Certificate bound — the document is now prepared and"
                                + " ready for signing.");
                showTasks();
            }

            @Override
            protected void failed() {
                showError(getException());
            }
        });
    }


    /* ------------------------------------------------------------------
     * internal navigation (Back = previously visited screen)
     * ------------------------------------------------------------------ */

    /**
     * Records a navigation to {@code screen}: the previously displayed
     * screen is kept so the Back button can return to it.
     */
    private void navigateTo(Runnable screen) {
        content.setPadding(new Insets(20));
        if (!navigatingBack && currentScreen != null) {
            screenHistory.push(currentScreen);
        }
        currentScreen = screen;
    }

    /** Re-renders the task screen in place without adding a Back step. */
    private void showTaskInPlace(String workflowId) {
        navigatingBack = true;
        try {
            showTask(workflowId);
        } finally {
            navigatingBack = false;
        }
    }

    /**
     * Back: shows the previously visited screen; when the current screen is
     * the first one of the session, leaves the dialog.
     */
    private void goBack() {
        if (journey.consumeBack()) {
            return;
        }
        Runnable previous = screenHistory.poll();
        if (previous == null) {
            dialog.close();
            return;
        }
        navigatingBack = true;
        try {
            currentScreen = previous;
            previous.run();
        } finally {
            navigatingBack = false;
        }
    }

    /* ------------------------------------------------------------------
     * plumbing
     * ------------------------------------------------------------------ */

    private void runInBackground(Task<?> task) {
        spinner.setVisible(true);
        task.setOnSucceeded(e -> spinner.setVisible(false));
        task.setOnFailed(e -> spinner.setVisible(false));
        Background.run("remote-workflow", task);
    }

    private void showError(Throwable t) {
        statusLabel.setText(ErrorMessages.generic(t));
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private HBox labeled(String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("page-subtitle");
        HBox box = new HBox(10, l, field);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
