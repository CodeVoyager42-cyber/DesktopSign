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
import com.example.signer.ui.dialog.InviterDialog;
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
        public boolean guestMode() {
            return guestMode;
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

    /**
     * When true, the dialog opens the "Send for signature" wizard right after
     * sign-in (the home screen's "Send for signature" action); Back from the
     * wizard lands on the signing tasks.
     */
    private boolean startWithNewWorkflow;

    /** See {@link #startWithNewWorkflow}; returns this dialog for chaining. */
    public RemoteWorkflowDialog startWithNewWorkflow(boolean value) {
        this.startWithNewWorkflow = value;
        return this;
    }

    /* ------------------------------------------------------------------
     * guest signing (invited signer, no account)
     * ------------------------------------------------------------------ */

    /** True when this dialog runs a guest signing session (no account). */
    private boolean guestMode;
    private String guestEmail;
    private String guestWorkflowId;

    /**
     * Opens the dialog for an INVITED SIGNER with a guest signing session:
     * no sign-in screen, no task lists, no owner tools — straight to the
     * one signing task the invitation is for.
     *
     * @param signerId the slot's signer id (as listed in the task)
     * @param email    the invited address, shown as "Signing as"
     */
    public void showGuestAndWait(Window owner, WorkflowApiClient guestClient,
                                 String signerId, String workflowId, String email,
                                 String documentName) {
        guestMode = true;
        guestEmail = email;
        guestWorkflowId = workflowId;
        guestDocumentName = documentName;
        try {
            showAndWait(owner, guestClient, signerId, workflowId);
        } finally {
            guestMode = false;
            guestEmail = null;
            guestWorkflowId = null;
        }
    }

    private String guestDocumentName;

    /** Guest: open the task — straight into the workspace when it is their turn. */
    private void openGuestTask() {
        content.getChildren().setAll(RemoteScreens.centered(RemoteScreens.loadingCard("Opening your signing task…")));
        runInBackground(new Task<TaskView>() {
            @Override
            protected TaskView call() {
                return client.task(guestWorkflowId);
            }

            @Override
            protected void succeeded() {
                TaskView task = getValue();
                TaskView.StepView mine = task.signers().stream()
                        .filter(s -> signedInUser.equals(s.userId()))
                        .findFirst().orElse(null);
                boolean canSign = !"DRAFT".equals(task.status()) && mine != null
                        && !mine.signed() && (mine.yourTurn() || task.waitingForClaim());
                if (canSign) {
                    journey.showReview(task);
                } else {
                    showTask(guestWorkflowId);
                }
            }

            @Override
            protected void failed() {
                journey.showFailure(getException(), null, RemoteWorkflowDialog.this::openGuestTask);
            }
        });
    }

    /** "Signing as": the invited address for a guest, else the account. */
    private String signingAsLabel() {
        return guestMode && guestEmail != null ? guestEmail : signedInUser;
    }

    /** Opens the wizard once, if the home screen asked for it. */
    private void afterSignedIn() {
        showTasks();
        if (startWithNewWorkflow) {
            startWithNewWorkflow = false;
            showCreateWorkflow();
        }
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
            if (guestMode) {
                dialog.setTitle("SignFlow — Sign "
                        + SigningTaskModels.readableTitle(guestDocumentName));
                statusLabel.setText("");
                openGuestTask();
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
                // Deliberately wrong credentials: a reachable server answers
                // 401 (task fails with an auth error); an unreachable one
                // fails with a connection error. Either way we learn the truth.
                WorkflowApiClient.login(baseUrl, "probe", "probe".toCharArray())
                        .myTasks();
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
        if (guestMode) {
            // A guest session only reaches its own task (the server refuses
            // the task lists): the "home" of a guest is that task.
            showTask(guestWorkflowId);
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
                        public void inviteSigner() {
                            openInviter();
                        }

                        @Override
                        public void manageWorkflows() {
                            showManaged();
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
     * 2b. workflows I manage (the owner's tracking view)
     * ------------------------------------------------------------------ */

    /** The signed-in user's own workflows: open one to track progress. */
    private void showManaged() {
        navigateTo(this::showManaged);
        runInBackground(new Task<List<WorkflowView>>() {
            private List<WorkflowView> owned;

            @Override
            protected List<WorkflowView> call() {
                owned = client.myWorkflows();
                return owned;
            }

            @Override
            protected void succeeded() {
                statusLabel.setText("Workflows you created — open one to track"
                        + " its signing progress and invitations.");
                VBox list = new VBox(10);
                if (owned.isEmpty()) {
                    VBox empty = new VBox(8);
                    empty.getStyleClass().add("empty-state");
                    empty.setAlignment(Pos.CENTER);
                    Label icon = new Label();
                    icon.setGraphic(new FontIcon(FontAwesomeSolid.FOLDER_OPEN));
                    icon.getStyleClass().add("empty-state-icon");
                    Label title = new Label("No workflows yet");
                    title.getStyleClass().add("empty-state-title");
                    Label hint = new Label("Create a signing workflow and invite"
                            + " signers by email — everything lands here for"
                            + " tracking.");
                    hint.getStyleClass().add("empty-state-hint");
                    hint.setWrapText(true);
                    empty.getChildren().addAll(icon, title, hint);
                    Button create = new Button("New signing workflow…",
                            new FontIcon(FontAwesomeSolid.PLUS));
                    create.getStyleClass().add("btn-primary");
                    create.setOnAction(e -> showCreateWorkflow());
                    empty.getChildren().add(create);
                    list.getChildren().add(empty);
                } else {
                    for (WorkflowView wf : owned) {
                        Button row = new Button(
                                wf.documentName() + "  —  "
                                        + wf.completedSignatures() + "/"
                                        + wf.totalSignatures() + " signed  —  "
                                        + wf.status(),
                                new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
                        row.getStyleClass().add("btn-secondary");
                        row.setMaxWidth(Double.MAX_VALUE);
                        row.setOnAction(e -> showTask(wf.workflowId()));
                        list.getChildren().add(row);
                    }
                }
                content.getChildren().setAll(
                        sectionTitle("Workflows I manage"), list);
            }

            @Override
            protected void failed() {
                showError(getException());
            }
        });
    }

    /* ------------------------------------------------------------------
     * invitation invite (server-side) + deep link
     * ------------------------------------------------------------------ */

    /** Opens the inviter bound to the signed-in session (JWT on every call). */
    private void openInviter() {
        InviterDialog inviter = new InviterDialog(client);
        inviter.showAndWait(dialog);
        InviterDialog.Result result = inviter.getResult();
        if (result == null) {
            // Closed without sending. The inviter reports its own refusals
            // (and logs them), so adding a second message here would only
            // guess at a reason the dialog already showed.
            return;
        }
        if (result.workflowId() != null) {
            statusLabel.setText("Invitation sent for signer slot "
                    + result.signerOrder() + ".");
            // The link exists only in the response that sent it, so show it
            // now — there is no later read that can reproduce it.
            if (result.inviteUrl() != null) {
                showInviteLink(result.workflowId(), result.signerOrder(),
                        result.inviteUrl());
            }
        } else if (result.token() != null) {
            statusLabel.setText("Invite link ready: " + result.inviteUrl()
                    + " — hand it to the signer to paste into the"
                    + " Invitation dialog.");
        } else {
            statusLabel.setText("No invitation was sent.");
        }
    }

    /**
     * Shows a just-emailed invitation link with a copy action. The server
     * returns the link once, when it sends it, and keeps only the token
     * hash — so this dialog is the sender's only look at what went out.
     */
    private void showInviteLink(String workflowId, int signerOrder, String url) {
        Alert alert = new Alert(
                Alert.AlertType.INFORMATION);
        alert.initOwner(dialog);
        alert.setTitle("Invitation sent");
        alert.setHeaderText("The invitation email for signer slot "
                + signerOrder + " of " + workflowId + " is on its way.");

        Label urlLabel = new Label(url);
        urlLabel.setWrapText(true);
        urlLabel.getStyleClass().add("mono-text");
        Label note = new Label("This is the exact link that was emailed."
                + " Keep it private — anyone who opens it can sign.");
        note.setWrapText(true);
        Button copy = new Button("Copy link",
                new FontIcon(FontAwesomeSolid.COPY));
        copy.setOnAction(e -> {
            NewWorkflowWizard.copyToClipboard(url);
            copy.setText("Copied");
        });
        alert.getDialogPane().setContent(new VBox(10, urlLabel, note, copy));
        alert.getDialogPane().getButtonTypes().add(
                ButtonType.CLOSE);
        alert.showAndWait();
    }

    /* ------------------------------------------------------------------
     * 3. task detail: signer progress + pre-sign review
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
            }            @Override
            protected void succeeded() {
                // Human header: document title + friendly progress; the
                // workflow id stays in the Details panel only (§4).
                statusLabel.setText("");
                SignFlowUi.Tone toneOk =
                        SignFlowUi.Tone.OK;
                long done = task.signers().stream()
                        .filter(TaskView.StepView::signed).count();
                int total = task.signers().size();
                TaskView.StepView mine = task.signers().stream()
                        .filter(s -> signedInUser.equals(s.userId()))
                        .findFirst().orElse(null);
                boolean draft = "DRAFT".equals(task.status());
                boolean needsKey = draft && mine != null && !mine.bound() && !mine.signed();
                boolean canSign = !draft && mine != null && !mine.signed()
                        && (mine.yourTurn() || task.waitingForClaim());
                boolean completed = total > 0 && done == total;

                // ---- header: document + one status chip --------------------
                FontIcon pdfIcon = new FontIcon(FontAwesomeSolid.FILE_PDF);
                pdfIcon.getStyleClass().add("sf-doc-icon");
                Label name = new Label(SigningTaskModels.readableTitle(task.documentName()));
                name.getStyleClass().add("sf-doc-name");
                name.setWrapText(true);
                String chipText;
                SignFlowUi.Tone chipTone;
                if (completed) {
                    chipText = "Completed";
                    chipTone = toneOk;
                } else if (needsKey) {
                    chipText = "Set up needed";
                    chipTone = SignFlowUi.Tone.WARN;
                } else if (canSign) {
                    chipText = "Your turn to sign";
                    chipTone = SignFlowUi.Tone.INFO;
                } else if (mine != null && mine.signed()) {
                    chipText = "Signed by you";
                    chipTone = toneOk;
                } else {
                    chipText = "Waiting for others";
                    chipTone = SignFlowUi.Tone.WARN;
                }
                Label chip = SignFlowUi.chip(chipText, chipTone);
                VBox titleBox = new VBox(6, name, chip);
                HBox header = new HBox(14, pdfIcon, titleBox);
                header.setAlignment(Pos.TOP_LEFT);

                // ---- progress ---------------------------------------------
                Label progressText = new Label(completed
                        ? "All " + total + " signatures complete"
                        : done + " of " + total + " signatures complete");
                progressText.getStyleClass().add("sf-detail-key");
                ProgressBar bar = new ProgressBar(total == 0 ? 0 : (double) done / total);
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.getStyleClass().add("task-progress-bar");

                // ---- the request, from the Signing Tasks list (real data) ----
                SigningTaskModels.SigningTask summary = guestMode || tasksView == null
                        ? null : tasksView.summaryOf(workflowId);
                VBox requestBox = new VBox(10);
                if (summary != null) {
                    Label who = new Label(summary.senderName() == null ? summary.explanation()
                            : summary.state() == SigningTaskModels.TaskState.ACTION_REQUIRED
                            ? summary.senderName() + " requested your signature."
                            : "Requested by " + summary.senderName() + ".");
                    who.getStyleClass().add("sf-text");
                    who.setWrapText(true);
                    requestBox.getChildren().addAll(who,
                            SignFlowUi.details(
                                    "Status", summary.explanation(),
                                    "Your role", summary.yourRole(),
                                    "Signing order", summary.anyOrder()
                                            ? "Any order (one signer at a time)"
                                            : summary.participants().isEmpty() ? null
                                            : "Fixed order, as listed below",
                                    "Created", summary.dateKind().equals("Sent")
                                            ? summary.dateText() : null,
                                    "Expires", summary.expiresText() == null ? null
                                            : summary.expiresText().replaceFirst("^Expire[sd] ", "")));
                }

                // ---- signers timeline --------------------------------------
                VBox signers = new VBox(8);
                for (TaskView.StepView step : task.signers()) {
                    signers.getChildren().add(signerRow(step, task));
                }

                // ---- what happens next + the one action --------------------
                VBox card;
                if (needsKey) {
                    Button bind = SignFlowUi.primary(
                            "Register my signing key", FontAwesomeSolid.ID_BADGE);
                    bind.setMaxWidth(Region.USE_PREF_SIZE);
                    bind.setOnAction(e -> captureAndBind(workflowId));
                    card = SignFlowUi.card(header, progressText, bar,
                            RemoteScreens.sectionLabel("Signing order"), signers,
                            SignFlowUi.note(FontAwesomeSolid.ID_CARD,
                                    SignFlowUi.Tone.WARN,
                                    "One-time setup: plug in your smart card and register"
                                            + " your signing key. The document is prepared"
                                            + " once every signer has done this."),
                            bind);
                } else {
                    String nextText = draft
                            ? "Nothing to do for now: waiting for the other signers to"
                                    + " register their signing keys. You'll be able to sign"
                                    + " afterwards."
                            : canSign
                            ? "Open the document, place your signature where you want it,"
                                    + " then sign with your smart card. The document is"
                                    + " checked on this computer before you sign."
                            : mine == null
                            ? completed ? "Everyone has signed. The signed document is"
                                            + " available from the SignFlow server."
                                    : "You sent this document. You can follow the"
                                            + " signatures here."
                            : mine.signed()
                            ? completed ? "Everyone has signed. Nothing else to do."
                                    : "Your signature is recorded. The others will be"
                                            + " asked in turn — nothing else to do."
                            : "Nothing to do for now: it isn't your turn yet. You'll see"
                                    + " this document under \"Needs your action\" when it is.";
                    HBox nextNote = SignFlowUi.note(
                            canSign ? FontAwesomeSolid.PEN_NIB
                                    : completed || (mine != null && mine.signed())
                                    ? FontAwesomeSolid.CHECK_CIRCLE : FontAwesomeSolid.HOURGLASS_HALF,
                            canSign ? SignFlowUi.Tone.INFO
                                    : completed || (mine != null && mine.signed()) ? toneOk
                                    : SignFlowUi.Tone.WARN,
                            nextText);
                    card = SignFlowUi.card(header, progressText, bar,
                            RemoteScreens.sectionLabel("Signing order"), signers, nextNote);
                    if (canSign) {
                        Button signButton = SignFlowUi.primary(
                                "Review & Sign", FontAwesomeSolid.ARROW_RIGHT);
                        signButton.setMaxWidth(Region.USE_PREF_SIZE);
                        signButton.setOnAction(e -> {
                            if (!task.waitingForClaim()) {
                                journey.showReview(task);
                                return;
                            }
                            // Flexible order: reserve the turn for this signer
                            // first (one signer at a time), then continue
                            // straight to signing — one click for the user.
                            runInBackground(new Task<TaskView>() {
                                @Override
                                protected TaskView call() {
                                    return client.claimTurn(workflowId);
                                }

                                @Override
                                protected void succeeded() {
                                    journey.showReview(getValue());
                                }

                                @Override
                                protected void failed() {
                                    showError(getException());
                                }
                            });
                        });
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
                if (!guestMode) {
                    Hyperlink backToTasks = new Hyperlink("← Back to Signing Tasks");
                    backToTasks.setOnAction(e -> showTasks());
                    taskColumn.getChildren().add(0, backToTasks);
                }
                taskColumn.setMaxWidth(720);
                card.setMaxWidth(720);
                content.getChildren().setAll(RemoteScreens.centered(taskColumn));
                loadInvitations(workflowId);
            }

            @Override
            protected void failed() {
                showError(getException());
            }
        });
    }

    /** One signer row: number/check, name (you), and a plain state chip. */
    private HBox signerRow(TaskView.StepView step, TaskView task) {
        // "Signed" is reserved for the server's own signature verdict on THIS
        // slot. An invited recipient who has not registered is shown as
        // invited, never as signed.
        String text;
        SignFlowUi.Tone tone;
        if (step.signed()) {
            text = "Signed";
            tone = SignFlowUi.Tone.OK;
        } else if ("INVITED".equals(step.signerState())) {
            text = "Invited — not signed yet";
            tone = SignFlowUi.Tone.WARN;
        } else if (step.yourTurn()) {
            text = signedInUser.equals(step.userId()) ? "Your turn" : "Signing now";
            tone = SignFlowUi.Tone.INFO;
        } else if (!step.bound()) {
            text = "Signing key not registered";
            tone = SignFlowUi.Tone.WARN;
        } else {
            text = "Waiting";
            tone = SignFlowUi.Tone.INFO;
        }
        Label number = new Label(step.signed() ? "" : String.valueOf(step.order()));
        if (step.signed()) {
            number.setGraphic(new FontIcon(FontAwesomeSolid.CHECK));
        }
        number.getStyleClass().addAll("sf-step-number", "task-signer-number",
                step.signed() ? "task-signer-number--done" : "task-signer-number--todo");
        boolean me = signedInUser.equals(step.userId());
        Label name = new Label(me ? step.userId() + " (you)" : step.userId());
        name.getStyleClass().add("sf-detail-value");
        if (me) {
            name.setStyle("-fx-font-weight: 700;");
        }
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(12, number, name, gap,
                SignFlowUi.chip(text, tone));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("task-signer-row");
        return row;
    }

    /* ------------------------------------------------------------------
     * 3b. create workflow (file picker + signer rows + token capture)
     * ------------------------------------------------------------------ */

    /**
     * The New-Workflow screen: pick the document, add one signer row per
     * participant (user id + certificate fingerprint), optionally capture
     * the fingerprint of the currently connected token, and submit.
     */
    /* ------------------------------------------------------------------
     * 3d. invitation tracking (owner only)
     * ------------------------------------------------------------------ */

    /**
     * Appends the per-slot invitation status card for the workflow's creator:
     * sent/accepted timestamps, status badges and a Resend action. Signers
     * who do not own the workflow simply never see the card (the server
     * answers 4xx and the section is skipped silently).
     */
    private void loadInvitations(String workflowId) {
        if (guestMode) {
            return; // owner-only information
        }
        runInBackground(new Task<List<ApiContracts.InvitationStatusView>>() {
            private ApiContracts.InvitationLinkInfo linkInfo;

            @Override
            protected List<ApiContracts.InvitationStatusView> call() {
                List<ApiContracts.InvitationStatusView> invitations =
                        client.workflowInvitations(workflowId);
                try {
                    linkInfo = client.invitationLinkInfo();
                } catch (RuntimeException ignored) {
                    linkInfo = null; // a server without the endpoint still shows the card
                }
                return invitations;
            }

            @Override
            protected void succeeded() {
                List<ApiContracts.InvitationStatusView> invitations = getValue();
                if (invitations == null || invitations.isEmpty()
                        || taskColumn == null || taskColumn.getScene() == null) {
                    return;
                }
                taskColumn.getChildren().add(InvitationsCard.build(
                        invitations, linkInfo, RemoteWorkflowDialog.this::resendInvitation));
            }
        });
    }

    /** Resend: invalidates the emailed link and emails a fresh one. */
    private void resendInvitation(ApiContracts.InvitationStatusView invitation,
                                  Consumer<ApiContracts.InvitationLinkView> onSent) {
        runInBackground(new Task<ApiContracts.InvitationLinkView>() {
            @Override
            protected ApiContracts.InvitationLinkView call() {
                return client.resendInvitation(invitation.invitationId());
            }

            @Override
            protected void succeeded() {
                statusLabel.setText("Fresh invitation emailed to " + invitation.email() + ".");
                onSent.accept(getValue());
            }

            @Override
            protected void failed() {
                showError(getException());
            }
        });
    }

    /**
     * New-workflow entry point: the guided wizard now owns the whole flow
     * (document → signers & order → review & send → success), rendering into
     * this dialog's content area so Back navigation keeps working.
     */
    private void showCreateWorkflow() {
        navigateTo(this::showCreateWorkflow);
        new NewWorkflowWizard(client, tokenService, dialog, content,
                statusLabel::setText, signedInUser,
                this::showTasks, this::openInviter, maxUploadBytes).start();
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
