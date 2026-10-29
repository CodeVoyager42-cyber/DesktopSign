package com.example.signer.ui.dialog;

import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.support.UiSupport;
import java.util.Locale;
import javafx.scene.control.Alert;
import com.example.signer.ui.support.Background;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.server.api.ApiContracts.InvitationStatusView;
import com.example.signer.server.api.ApiContracts.InvitationView;
import com.example.signer.server.api.ApiContracts.WorkflowView;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-side inviter (the missing slice of the Phase-4 acceptance flow):
 * <ol>
 *   <li>pick one of the signed-in user's workflows and a signer slot,</li>
 *   <li>{@code POST /api/workflows/{id}/invitations} (authenticated via the
 *   dialog's client) creates the invitation and the {@code EmailService}
 *   delivers the email,</li>
 *   <li>the deep link it carries is printed to the server log in console
 *   mode. The raw token is NEVER returned by the API — only its SHA-256
 *   hash is stored server-side — so a successful send is reported as
 *   "emailed", not as a link,</li>
 *   <li>the invited signer pastes that link into {@link InvitationDialog} to
 *   review, register, and sign.</li>
 * </ol>
 *
 * <p><strong>The button is never a silent dead end.</strong> An invitation
 * can only be addressed to the email the signer slot is assigned to (the
 * server enforces this with {@code 403 EMAIL_MISMATCH}), so the dialog now
 * reads the slot's own address from the server and offers it in the email
 * field, and every click reports what happened: a loud message in the dialog
 * plus a log line on the desktop console. A disabled button with no
 * explanation — which looks exactly like "I clicked and nothing happened" —
 * is no longer reachable: validation happens on click, and the only state
 * that disables Send is a request already in flight.
 */
public final class InviterDialog {

    private static final Logger log = LoggerFactory.getLogger(InviterDialog.class);

    /**
     * Outcome of an invitation attempt. {@code workflowId != null} means the
     * invite was SENT; {@code token != null} means the user pasted an
     * existing link (which is handed over unchanged, never validated here).
     * {@code inviteUrl} is the exact link the server just emailed — returned
     * once, at send time, because only the token hash is ever stored.
     */
    public record Result(String workflowId, int signerOrder, String token,
                         String inviteUrl) {
    }

    /** The signed-in user's client (JWT + silent refresh) used for the call. */
    private final WorkflowApiClient client;
    private final AtomicReference<Result> result = new AtomicReference<>();

    public InviterDialog(WorkflowApiClient client) {
        this.client = client;
    }

    public Result getResult() {
        return result.get();
    }

    public void showAndWait(Window owner) {
        if (client == null) {
            log.warn("Inviter opened without an authenticated client");
            Alert alert = new Alert(
                    Alert.AlertType.WARNING);
            if (owner != null) {
                alert.initOwner(owner);
            }
            alert.setTitle("Not signed in");
            alert.setHeaderText(null);
            alert.setContentText("Sign in first — an invitation is sent as the"
                    + " signed-in workflow owner.");
            alert.showAndWait();
            return;
        }
        new Panel().show(owner);
    }

    /* ------------------------------------------------------------------ */

    /**
     * The dialog's controls and behaviour. One instance per opening; every
     * user action ends in a visible message and a log line.
     */
    private final class Panel {

        private final Stage dialog = new Stage(StageStyle.DECORATED);
        private final ProgressIndicator spinner = new ProgressIndicator();
        private final Label status = new Label();
        private final Label slotInfo = new Label();
        private final Label hint = new Label();
        private final ComboBox<String> workflowPicker = new ComboBox<>();
        private final ComboBox<Integer> slotPicker = new ComboBox<>();
        private final TextField emailField = new TextField();
        private final Button sendButton = new Button("Send invite",
                new FontIcon(FontAwesomeSolid.ENVELOPE));
        private final Button reloadButton = new Button("Reload list",
                new FontIcon(FontAwesomeSolid.SYNC));
        private final Button pasteButton = new Button("or paste an invite link",
                new FontIcon(FontAwesomeSolid.LINK));

        /** label → workflow public id, and label → signer slots. */
        private final Map<String, String> idByLabel = new LinkedHashMap<>();
        private final Map<String, List<Integer>> slotsByLabel = new LinkedHashMap<>();
        /** slot order → its invitation row (address + state), per workflow. */
        private final Map<Integer, InvitationStatusView> slotInvites = new HashMap<>();

        private boolean loading;
        private boolean sending;
        private String loadedWorkflowId;
        private String lastAutoFilled;

        void show(Window owner) {
            dialog.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) {
                dialog.initOwner(owner);
            }
            dialog.setTitle("Invite signer to sign");

            spinner.setPrefSize(16, 16);
            spinner.getStyleClass().add("busy-spinner");
            spinner.setVisible(false);
            spinner.setManaged(false);

            Label title = new Label("Send an invitation");
            Region titleGap = new Region();
            HBox.setHgrow(titleGap, Priority.ALWAYS);
            HBox header = new HBox(10, title, titleGap, spinner);
            header.setAlignment(Pos.CENTER_LEFT);

            banner(status, "", null);

            workflowPicker.setPromptText("Your workflow");
            workflowPicker.setMaxWidth(Double.MAX_VALUE);
            slotPicker.setPromptText("Signer slot");
            slotPicker.setMaxWidth(Double.MAX_VALUE);

            workflowPicker.valueProperty().addListener((o, was, label) -> {
                slotPicker.getItems().setAll(label == null ? List.of()
                        : slotsByLabel.getOrDefault(label, List.of()));
                slotPicker.setDisable(slotPicker.getItems().isEmpty());
                if (!slotPicker.getItems().isEmpty()) {
                    slotPicker.getSelectionModel().selectFirst();
                } else {
                    slotPicker.setValue(null);
                }
                onWorkflowSelected();
            });

            // The slot decides whose address the invitation must carry, so a
            // slot change re-renders the slot banner (and the offered email).
            slotPicker.valueProperty().addListener((o, was, is) ->
                    updateSlotInfo());

            slotInfo.setWrapText(true);
            slotInfo.getStyleClass().add("page-subtitle");
            slotInfo.setVisible(false);
            slotInfo.setManaged(false);

            emailField.setPromptText("Signer's email address — the one the"
                    + " invitation is addressed to");
            emailField.setPrefColumnCount(42);
            emailField.textProperty().addListener((o, was, is) -> refreshHint());

            hint.setWrapText(true);
            hint.setMaxWidth(Double.MAX_VALUE);
            hint.getStyleClass().add("page-subtitle");

            sendButton.getStyleClass().add("btn-primary");
            sendButton.setDefaultButton(true);
            sendButton.setOnAction(e -> attemptSend());

            reloadButton.getStyleClass().add("btn-ghost");
            reloadButton.setVisible(false);
            reloadButton.setManaged(false);
            reloadButton.setOnAction(e -> loadOwnedWorkflows());

            pasteButton.getStyleClass().add("btn-ghost");
            pasteButton.setTooltip(new Tooltip(
                    "Paste the signflow://invitation/… link the owner already"
                            + " emailed — skips step 1"));
            pasteButton.setDisable(true);
            emailField.textProperty().addListener((o, was, is) ->
                    pasteButton.setDisable(is.isBlank()));
            pasteButton.setOnAction(e -> pasteLink());

            Button cancelButton = new Button("Cancel");
            cancelButton.getStyleClass().add("btn-ghost");
            cancelButton.setCancelButton(true);
            cancelButton.setOnAction(e -> dialog.close());

            VBox emailBox = new VBox(4, new Label("Signer email"), emailField);
            emailBox.setAlignment(Pos.TOP_LEFT);

            // Absorbs the spare height so the action row stays at the bottom.
            Region push = new Region();
            VBox.setVgrow(push, Priority.ALWAYS);

            HBox actions = new HBox(10, reloadButton, sendButton, pasteButton,
                    cancelButton);
            actions.setAlignment(Pos.CENTER_RIGHT);

            VBox root = new VBox(12, header, status, workflowPicker, slotPicker,
                    slotInfo, emailBox, push, hint, actions);
            root.getStyleClass().add("wf-root");
            root.setPadding(new Insets(18));

            Scene scene = new Scene(root, 640, 540);
            ThemeManager.instance().attach(scene);
            dialog.setScene(scene);

            refreshHint();
            loadOwnedWorkflows();

            UiSupport.applyAppIcon(dialog);
            dialog.showAndWait();
        }

        /* -------------------------------------------------------------- */
        /* workflow list                                                   */
        /* -------------------------------------------------------------- */

        private void loadOwnedWorkflows() {
            if (loading) {
                return;
            }
            loading = true;
            showSpinner(true);
            banner(status, "", null);
            hint.setText("Loading your workflows…");
            reloadButton.setVisible(false);
            reloadButton.setManaged(false);
            Task<List<WorkflowView>> load = new Task<>() {
                @Override
                protected List<WorkflowView> call() {
                    return client.myWorkflows();
                }

                @Override
                protected void succeeded() {
                    loading = false;
                    showSpinner(false);
                    applyWorkflows(getValue());
                }

                @Override
                protected void failed() {
                    loading = false;
                    showSpinner(false);
                    Throwable error = getException();
                    log.warn("Could not load the signed-in user's workflows: {}",
                            error == null ? "unknown" : error.toString());
                    banner(status, "error", friendly(error,
                            "The workflow list could not be loaded — check the"
                                    + " server connection."));
                    hint.setText("Press \"Reload list\" to try again.");
                    reloadButton.setVisible(true);
                    reloadButton.setManaged(true);
                }
            };
            start(load, "invite-load-owned");
        }

        private void applyWorkflows(List<WorkflowView> owned) {
            idByLabel.clear();
            slotsByLabel.clear();
            slotInvites.clear();
            slotInfo.setVisible(false);
            slotInfo.setManaged(false);
            // lastAutoFilled survives a reload on purpose: an address this
            // dialog offered may still be replaced by the next slot's own.

            if (owned == null || owned.isEmpty()) {
                workflowPicker.getItems().clear();
                workflowPicker.setValue(null);
                log.info("Inviter: the signed-in user owns no workflows");
                banner(status, "warn", "You have no workflows to invite into"
                        + " yet — create one first with \"New workflow\".");
                hint.setText("Nothing to invite into yet.");
                reloadButton.setVisible(true);
                reloadButton.setManaged(true);
                return;
            }

            List<String> labels = new ArrayList<>();
            for (WorkflowView wf : owned) {
                String label = wf.documentName() + "  —  " + wf.workflowId()
                        + "  (" + wf.totalSignatures() + " signer(s))";
                labels.add(label);
                idByLabel.put(label, wf.workflowId());
                List<Integer> slots = new ArrayList<>();
                for (int i = 1; i <= wf.totalSignatures(); i++) {
                    slots.add(i);
                }
                slotsByLabel.put(label, slots);
            }
            workflowPicker.getItems().setAll(labels);
            workflowPicker.getSelectionModel().selectFirst();
            log.info("Inviter: loaded {} workflow(s) owned by the signed-in"
                    + " user", labels.size());
            banner(status, "", null);
            refreshHint();
        }

        /* -------------------------------------------------------------- */
        /* slot identity (who the slot belongs to)                         */
        /* -------------------------------------------------------------- */

        private void onWorkflowSelected() {
            String workflowId = idByLabel.get(workflowPicker.getValue());
            slotInvites.clear();
            slotInfo.setVisible(false);
            slotInfo.setManaged(false);
            loadedWorkflowId = workflowId;
            refreshHint();
            if (workflowId == null) {
                return;
            }
            Task<List<InvitationStatusView>> slotTask = new Task<>() {
                @Override
                protected List<InvitationStatusView> call() {
                    return client.workflowInvitations(workflowId);
                }

                @Override
                protected void succeeded() {
                    if (!workflowId.equals(loadedWorkflowId)) {
                        return;
                    }
                    List<InvitationStatusView> rows = getValue();
                    if (rows != null) {
                        for (InvitationStatusView row : rows) {
                            slotInvites.put(row.signerOrder(), row);
                        }
                    }
                    updateSlotInfo();
                }

                @Override
                protected void failed() {
                    // Not fatal: the email can still be typed by hand, and
                    // the server remains the authority on the slot address.
                    log.debug("Could not read the slot invitation state of {}:"
                            + " {}", workflowId, getException() == null
                            ? "unknown" : getException().toString());
                }
            };
            start(slotTask, "invite-slot-state");
        }

        private void updateSlotInfo() {
            InvitationStatusView inv = slotInvites.get(slotPicker.getValue());
            if (inv == null) {
                slotInfo.setVisible(false);
                slotInfo.setManaged(false);
                refreshHint();
                return;
            }
            String email = inv.email();
            String state = inv.slotSigned() ? "already signed"
                    : switch (inv.invitationStatus()) {
                        case PENDING -> "invited — not signed";
                        case ACCEPTED -> "accepted — not signed";
                        case REVOKED -> "invitation revoked";
                        case EXPIRED -> "invitation expired";
                        default -> inv.status() == null ? "no invitation yet"
                                : inv.status().toLowerCase(Locale.ROOT);
                    };
            slotInfo.setText("Signer " + inv.signerOrder() + " is assigned to "
                    + (email == null || email.isBlank()
                            ? "no email address" : email)
                    + " (" + state + ").");
            slotInfo.setVisible(true);
            slotInfo.setManaged(true);

            // Offer the slot's own address: an invitation is only ever
            // accepted for it, so typing anything else is refused by the
            // server (403 EMAIL_MISMATCH).
            if (email != null && !email.isBlank()
                    && (emailField.getText().isBlank()
                        || emailField.getText().trim().equals(lastAutoFilled))) {
                emailField.setText(email);
                lastAutoFilled = email;
            }
            refreshHint();
        }

        /* -------------------------------------------------------------- */
        /* sending                                                         */
        /* -------------------------------------------------------------- */

        private void attemptSend() {
            if (sending) {
                return;
            }
            if (loading) {
                banner(status, "info", "Still loading your workflows — one"
                        + " moment.");
                return;
            }
            String email = emailField.getText() == null ? ""
                    : emailField.getText().trim();
            String label = workflowPicker.getValue();
            Integer slot = slotPicker.getValue();
            String workflowId = idByLabel.get(label);

            if (workflowId == null) {
                log.info("Send invite clicked with no workflow selected");
                banner(status, "error", workflowPicker.getItems().isEmpty()
                        ? "There is no workflow to invite into — create one"
                                + " first with \"New workflow\"."
                        : "Select one of your workflows first.");
                return;
            }
            if (slot == null) {
                log.info("Send invite clicked with no signer slot selected for"
                        + " {}", workflowId);
                banner(status, "error", "Select the signer slot the invitation"
                        + " is for.");
                return;
            }
            if (email.isEmpty()) {
                log.info("Send invite clicked without an email address for {}"
                        + " signer {}", workflowId, slot);
                banner(status, "error", "Type the signer's email address — the"
                        + " invitation can only be addressed to the address"
                        + " that signer slot is assigned to.");
                return;
            }

            log.info("Sending invitation: workflow={} signer={} email={}",
                    workflowId, slot, email);
            sending = true;
            showSpinner(true);
            sendButton.setDisable(true);
            sendButton.setText("Sending…");
            banner(status, "info", "Sending the invitation for signer " + slot
                    + " to " + email + " …");
            Task<InvitationView> task = new Task<>() {
                @Override
                protected InvitationView call() {
                    return client.invite(workflowId, slot, email);
                }

                @Override
                protected void succeeded() {
                    InvitationView view = getValue();
                    log.info("Invitation sent: workflow={} signer={} email={}"
                            + " invitationId={}", workflowId, slot, email,
                            view == null ? null : view.invitationId());
                    // Success marker = the workflow we invited into. The link
                    // is handed over so the sender can see what went out; the
                    // raw token itself is still never stored server-side.
                    result.set(new Result(view == null ? workflowId
                            : view.workflowId(), slot, null,
                            view == null ? null : view.invitationUrl()));
                    dialog.close();
                }

                @Override
                protected void failed() {
                    sending = false;
                    showSpinner(false);
                    sendButton.setDisable(false);
                    sendButton.setText("Send invite");
                    Throwable error = getException();
                    String message = friendly(error,
                            "The invite could not be sent — check the signer's"
                                    + " email and try again.");
                    log.warn("Invitation failed: workflow={} signer={} email={}"
                            + " error={}", workflowId, slot, email, message);
                    banner(status, "error", message);
                    // One retry of a superseded or refused send must not need
                    // the dialog reopened: keep the refresh affordance handy.
                    reloadButton.setVisible(true);
                    reloadButton.setManaged(true);
                }
            };
            start(task, "invite-create");
        }

        private void pasteLink() {
            String raw = emailField.getText() == null ? ""
                    : emailField.getText().trim();
            if (raw.isEmpty()) {
                banner(status, "error", "Paste the invite link into the field"
                        + " above.");
                return;
            }
            String token = extractToken(raw);
            if (token == null) {
                banner(status, "error", "That does not look like an invite"
                        + " link.");
                return;
            }
            log.info("Invite link pasted by hand ({} characters) — handed to"
                    + " the signer without contacting the server",
                    token.length());
            result.set(new Result(null, 1, token,
                    "signflow://invitation/" + token));
            dialog.close();
        }

        /* -------------------------------------------------------------- */
        /* rendering helpers                                               */
        /* -------------------------------------------------------------- */

        /** Why Send is where it is: always explains what is still missing. */
        private void refreshHint() {
            if (loading) {
                hint.setText("Loading your workflows…");
                return;
            }
            String workflowId = idByLabel.get(workflowPicker.getValue());
            if (workflowId == null) {
                hint.setText(workflowPicker.getItems().isEmpty()
                        ? "Nothing to invite into yet — create a workflow"
                                + " first."
                        : "Select the workflow the invitation is for.");
                return;
            }
            Integer slot = slotPicker.getValue();
            if (slot == null) {
                hint.setText("Select the signer slot — every slot holds exactly"
                        + " one signer's address.");
                return;
            }
            String email = emailField.getText() == null ? ""
                    : emailField.getText().trim();
            boolean signed = slotInvites.get(slot) != null
                    && slotInvites.get(slot).slotSigned();
            if (email.isEmpty()) {
                hint.setText("Enter the email of the signer in slot " + slot
                        + " — the invitation can only go to that address.");
            } else if (signed) {
                hint.setText("Signer " + slot + " has already signed; a new"
                        + " invitation is only needed to replace the link.");
            } else {
                hint.setText("Ready: the invitation for signer " + slot
                        + " will be emailed to " + email + ".");
            }
        }

        /**
         * The spinner is a real node in this scene (it used to be created,
         * then never added — so "loading" looked like nothing happening).
         */
        private void showSpinner(boolean state) {
            spinner.setVisible(state);
            spinner.setManaged(state);
        }

        /**
         * The single message area: kind is {@code "error"}, {@code "warn"},
         * {@code "success"} or {@code null}/{@code "info"}. An empty message
         * collapses the banner so it never shows dead space.
         */
        private void banner(Label label, String kind, String text) {
            label.getStyleClass().removeIf(style ->
                    style.startsWith("wf-banner--") || style.equals("card"));
            label.setText(text == null ? "" : text);
            label.setWrapText(true);
            label.setMaxWidth(Double.MAX_VALUE);
            boolean empty = text == null || text.isBlank();
            label.setVisible(!empty);
            label.setManaged(!empty);
            if (empty) {
                return;
            }
            switch (kind == null ? "info" : kind) {
                case "error" -> label.getStyleClass().addAll("card",
                        "wf-banner--danger");
                case "warn" -> label.getStyleClass().addAll("card",
                        "wf-banner--warn");
                case "success" -> label.getStyleClass().addAll("card",
                        "wf-banner--success");
                default -> label.getStyleClass().add("card");
            }
        }

        private void start(Task<?> task, String threadName) {
            Background.run(threadName, task);
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * Accepts a raw token, or the full deep link
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
}
