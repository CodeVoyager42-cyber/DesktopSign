package com.example.signer.ui.dialog;

import com.example.signer.ui.ThemeManager;
import com.example.signer.server.api.ApiContracts;
import com.example.signer.ui.support.UiSupport;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import com.example.signer.ui.support.Background;
import com.example.signer.server.api.ApiContracts.InvitationPreviewView;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.client.InvitationState;
import com.example.signer.ui.kit.SignFlowUi;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Invitation UI for the INVITED SIGNER, who needs no SignFlow account: the
 * signer opens (or pastes) the emailed link, sees what they are asked to
 * sign — document, requester, invited address, expiry — and clicks
 * "Review &amp; Sign". The server validates the link's token and opens a
 * guest signing session limited to that one signing task. There is no
 * username or password anywhere in this flow.
 *
 * <p>Two different things, kept apart on purpose: the invitation token only
 * AUTHORIZES access to the signing task; the digital signature itself is
 * created later with the signer's own certificate on their smart card.
 *
 * <p>Every invitation state (valid, expired, revoked, already used,
 * completed, invalid, network/server error) has its own screen; errors are
 * friendly text,
 * never stack traces.
 */
public final class InvitationDialog {

    /** Outcome of a completed invitation acceptance. */
    /**
     * Outcome: a GUEST signing session for one signing task (no account).
     *
     * @param signerId the slot's signer id as it appears in the task
     * @param email    the invited address (invitation data, shown as
     *                 "Signing as" — NOT the digital signature's identity,
     *                 which comes from the certificate on the smart card)
     */
    public record Result(String signerId, WorkflowApiClient client,
                         String workflowId, String email, String documentName) {
    }

    private final String baseUrl;
    private final String initialToken;
    private final AtomicReference<Result> result = new AtomicReference<>();

    public InvitationDialog(String baseUrl) {
        this(baseUrl, null);
    }

    /**
     * @param initialToken token taken from a {@code signflow://invitation/…}
     *                     deep link; when present the dialog skips the paste
     *                     step and goes straight to the invitation preview.
     */
    public InvitationDialog(String baseUrl, String initialToken) {
        this.baseUrl = baseUrl;
        this.initialToken = initialToken;
    }

    /** The acceptance outcome, or {@code null} when cancelled/failed. */
    public Result getResult() {
        return result.get();
    }

    /** Steps shown in the rail. */
    private static final List<String> STEPS = List.of(
            "Invitation", "Document", "Signature", "Confirm");

    private Stage dialog;
    private BorderPane shell;
    private ProgressIndicator spinner;
    private Label status;

    /** Shows the modal dialog; blocks until closed. */
    public void showAndWait(Window owner) {
        dialog = new Stage(StageStyle.DECORATED);
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("SignFlow — Invitation to sign");
        dialog.setMinWidth(720);
        dialog.setMinHeight(520);

        spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);
        status = new Label();
        status.getStyleClass().add("sf-text");
        status.setWrapText(true);

        shell = SignFlowUi.shell(SignFlowUi.stepRail(STEPS, 0), new VBox());
        Scene scene = new Scene(shell, 900, 620);
        ThemeManager.instance().attach(scene);
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);

        if (initialToken != null && !initialToken.isBlank()) {
            // Arrived from the emailed link: no manual paste needed.
            open(initialToken);
        } else {
            showPasteStep(null);
        }
        dialog.showAndWait();
    }

    /** Replaces the centered content and the rail's current step. */
    private void show(int step, Node content) {
        shell.setLeft(SignFlowUi.stepRail(STEPS, step));
        StackPane center = new StackPane(content);
        center.setAlignment(Pos.TOP_CENTER);
        center.setPadding(new Insets(28));
        ((ScrollPane) shell.getCenter()).setContent(center);
    }

    /** No link yet: paste it. */
    private void showPasteStep(String problem) {
        TextField tokenField = new TextField();
        tokenField.setPromptText("Paste the link from your invitation email");
        Button open = SignFlowUi.primary("Open invitation", FontAwesomeSolid.ARROW_RIGHT);
        open.setDisable(true);
        tokenField.textProperty().addListener((o, was, is) -> open.setDisable(is.isBlank()));
        open.setOnAction(e -> {
            String token = extractToken(tokenField.getText());
            if (token == null) {
                showPasteStep("That does not look like an invitation link. Copy the"
                        + " full link from the email.");
                return;
            }
            open(token);
        });
        Button cancel = SignFlowUi.secondary("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> dialog.close());
        VBox card = SignFlowUi.card(
                SignFlowUi.stateIcon(FontAwesomeSolid.ENVELOPE_OPEN, SignFlowUi.Tone.INFO),
                SignFlowUi.title("Open your invitation"),
                SignFlowUi.text("Someone asked you to sign a document. Paste the link"
                        + " from their email below — it looks like"
                        + " https://…/invitations/… or signflow://invitation/…"),
                tokenField,
                open,
                new HBox(10, cancel));
        if (problem != null) {
            card.getChildren().add(3, SignFlowUi.note(FontAwesomeSolid.EXCLAMATION_TRIANGLE,
                    SignFlowUi.Tone.WARN, problem));
        }
        show(0, card);
        tokenField.requestFocus();
    }

    /** Loading screen while the server validates the token. */
    private void open(String token) {
        ProgressIndicator big = new ProgressIndicator();
        big.setPrefSize(46, 46);
        VBox loading = SignFlowUi.card(big,
                SignFlowUi.title("Opening invitation…"),
                SignFlowUi.text("Verifying the invitation with the SignFlow server."));
        loading.setAlignment(Pos.TOP_CENTER);
        show(0, loading);
        run(new Task<InvitationPreviewView>() {
            @Override
            protected InvitationPreviewView call() {
                return WorkflowApiClient.invitationPreview(baseUrl, token);
            }

            @Override
            protected void succeeded() {
                InvitationPreviewView preview = getValue();
                InvitationState state = InvitationState.of(preview);
                if (state == InvitationState.VALID) {
                    showValid(preview, token);
                } else {
                    showState(state, token);
                }
            }

            @Override
            protected void failed() {
                showState(InvitationState.of(getException()), token);
            }
        });
    }

    /** Any non-valid state: own icon, title, explanation and action. */
    private void showState(InvitationState state, String token) {
        Ikon icon;
        SignFlowUi.Tone tone;
        switch (state) {
            case COMPLETED -> {
                icon = FontAwesomeSolid.CHECK_CIRCLE;
                tone = SignFlowUi.Tone.OK;
            }
            case EXPIRED -> {
                icon = FontAwesomeSolid.HOURGLASS_END;
                tone = SignFlowUi.Tone.WARN;
            }
            case ALREADY_USED -> {
                icon = FontAwesomeSolid.CHECK_DOUBLE;
                tone = SignFlowUi.Tone.INFO;
            }
            case NETWORK_ERROR -> {
                icon = FontAwesomeSolid.WIFI;
                tone = SignFlowUi.Tone.ERROR;
            }
            case SERVER_ERROR -> {
                icon = FontAwesomeSolid.SERVER;
                tone = SignFlowUi.Tone.ERROR;
            }
            default -> {
                icon = FontAwesomeSolid.BAN;
                tone = SignFlowUi.Tone.ERROR;
            }
        }
        Button close = SignFlowUi.secondary("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> dialog.close());
        Button primary;
        if (state.retryable()) {
            primary = SignFlowUi.primary("Try again", FontAwesomeSolid.SYNC);
            primary.setMaxWidth(Region.USE_PREF_SIZE);
            primary.setOnAction(e -> open(token));
        } else if (state == InvitationState.INVALID) {
            primary = SignFlowUi.primary("Paste a different link", null);
            primary.setMaxWidth(Region.USE_PREF_SIZE);
            primary.setOnAction(e -> showPasteStep(null));
        } else {
            primary = null;
        }
        VBox panel = primary == null
                ? SignFlowUi.statePanel(icon, tone, state.title(), state.explanation(), close)
                : SignFlowUi.statePanel(icon, tone, state.title(), state.explanation(),
                        primary, close);
        show(0, panel);
    }

    /** The valid invitation: who asks, what, as whom, until when. */
    private void showValid(InvitationPreviewView preview, String token) {
        FontIcon docIcon = new FontIcon(FontAwesomeSolid.FILE_PDF);
        docIcon.getStyleClass().add("sf-doc-icon");
        Label docName = new Label(preview.documentName());
        docName.getStyleClass().add("sf-doc-name");
        docName.setWrapText(true);
        HBox docRow = new HBox(14, docIcon, new VBox(4, docName,
                new HBox(8, SignFlowUi.chip("PDF", SignFlowUi.Tone.INFO),
                        SignFlowUi.chip(InvitationState.VALID.title(), SignFlowUi.Tone.OK))));
        docRow.setAlignment(Pos.CENTER_LEFT);

        Button reviewAndSign = SignFlowUi.primary("Review & Sign", FontAwesomeSolid.ARROW_RIGHT);
        reviewAndSign.setOnAction(e -> openGuestSession(preview, token));
        Button close = SignFlowUi.secondary("Not now");
        close.setCancelButton(true);
        close.setOnAction(e -> dialog.close());

        VBox card = SignFlowUi.card(
                SignFlowUi.title("You have been invited to sign"),
                docRow,
                new Separator(),
                SignFlowUi.details(
                        "Requested by", preview.invitedBy(),
                        "Signing as", preview.invitedEmail(),
                        "Expires", SignFlowUi.dateTime(SignFlowUi.parseInstant(preview.expiresAt()))),
                SignFlowUi.text(InvitationState.VALID.explanation()),
                SignFlowUi.note(FontAwesomeSolid.SHIELD_ALT, SignFlowUi.Tone.INFO,
                        "Secure invitation. No SignFlow account is required: this link"
                                + " opens only this signing task. Your digital signature"
                                + " is made on this computer with your own smart card —"
                                + " your PIN never leaves it."),
                reviewAndSign,
                new HBox(10, close));
        show(0, card);
        reviewAndSign.requestFocus();
    }

    /**
     * "Review & Sign": the server validates the link's token and opens a
     * GUEST signing session scoped to this one signing task. No username,
     * no password, no account. (The token only authorizes access; the
     * digital signature is made later with the signer's own certificate.)
     */
    private void openGuestSession(InvitationPreviewView preview, String token) {
        ProgressIndicator big = new ProgressIndicator();
        big.setPrefSize(46, 46);
        VBox loading = SignFlowUi.card(big,
                SignFlowUi.title("Opening your signing task…"),
                SignFlowUi.text("Preparing a secure signing session for "
                        + preview.documentName() + "."));
        loading.setAlignment(Pos.TOP_CENTER);
        show(1, loading);
        run(new Task<ApiContracts.GuestSessionView>() {
            @Override
            protected ApiContracts.GuestSessionView call() {
                return WorkflowApiClient.openGuestSession(baseUrl, token);
            }

            @Override
            protected void succeeded() {
                var session = getValue();
                result.set(new Result(session.signerId(),
                        WorkflowApiClient.forGuest(baseUrl, session),
                        session.workflowId(), session.email(), session.documentName()));
                dialog.close();
            }

            @Override
            protected void failed() {
                showState(InvitationState.of(getException()), token);
            }
        });
    }

    private void run(Task<?> task) {
        spinner.setVisible(true);
        task.setOnSucceeded(e -> spinner.setVisible(false));
        task.setOnFailed(e -> spinner.setVisible(false));
        Background.run("invitation", task);
    }

    /**
     * Accepts the raw token, or the full deep link
     * ({@code signflow://invitation/<token>}).
     */
    private static String extractToken(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 && idx < trimmed.length() - 1
                ? trimmed.substring(idx + 1) : trimmed;
    }

    private static String friendly(Throwable t, String fallback) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.isBlank() ? fallback
                : firstSentence(message);
    }

    private static String firstSentence(String message) {
        int dot = message.indexOf(". ");
        return dot > 0 ? message.substring(0, dot + 1) : message;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
