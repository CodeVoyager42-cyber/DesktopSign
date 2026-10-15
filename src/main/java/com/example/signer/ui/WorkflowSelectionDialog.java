package com.example.signer.ui;

import javafx.application.Platform;
import javafx.scene.AccessibleRole;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import com.example.signer.ui.support.UiSupport;
import com.example.signer.workflow.SigningMode;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.List;

/**
 * Home screen ("What would you like to do?"), shown after sign-in. Four
 * large one-click actions cover the everyday tasks — sign here, send for
 * signature, documents to sign, open an invitation — and the less common
 * modes (several signers on one computer, continuing a signing PDF) are
 * small links underneath.
 *
 * <p>Presents one selectable card per {@link SigningMode} (driven from
 * {@link SigningMode#selectableModes()}, so new modes appear automatically).
 * Single selection via mouse or keyboard (arrows to move, Enter/Space to
 * select, Enter on Continue to proceed), a disabled Continue until a card is
 * chosen, and Back / Sign out for the bottom navigation. The chosen mode is
 * returned to the caller; {@code null} means the user backed out.</p>
 *
 * <p>Styling lives in {@code application.css} ({@code .workflow-*} classes)
 * and follows the shadcn-style token design system, including dark mode via
 * {@link ThemeManager}.</p>
 */
public final class WorkflowSelectionDialog {

    private final List<SigningMode> modes;
    private final ObjectProperty<SigningMode> selected = new SimpleObjectProperty<>(this, "selected");
    private boolean cancelled = true;

    /** Set when the user chose to open an existing (portable) workflow PDF. */
    private boolean openWorkflowRequested;

    /** Set when the user chose “Remote workflow…” on this dialog. */
    private boolean remoteWorkflowRequested;

    /** Set when the user pressed Back (as opposed to closing the window). */
    private boolean backRequested;

    /** Set when the user chose “Accept invitation…” on this dialog. */
    private boolean acceptInvitationRequested;

    /** Set when the user chose “Sign out” on this dialog. */
    private boolean signOutRequested;

    /** @return true when the user picked “Open workflow…” on this dialog. */
    public boolean isOpenWorkflowRequested() {
        return openWorkflowRequested;
    }

    /** @return true when the user picked “Remote workflow…” on this dialog. */
    public boolean isRemoteWorkflowRequested() {
        return remoteWorkflowRequested;
    }

    /** @return true when the user pressed Back on this dialog. */
    public boolean isBackRequested() {
        return backRequested;
    }

    /** @return true when the user picked “Accept invitation…” on this dialog. */
    public boolean isAcceptInvitationRequested() {
        return acceptInvitationRequested;
    }

    /** @return true when the user chose “Sign out” on this dialog. */
    public boolean isSignOutRequested() {
        return signOutRequested;
    }

    public WorkflowSelectionDialog() {
        this(SigningMode.selectableModes());
    }

    /** Allows tests/demos to show a different (e.g. extended) mode list. */
    public WorkflowSelectionDialog(List<SigningMode> modes) {
        this.modes = List.copyOf(modes);
    }

    /**
     * Shows the modal selection dialog.
     *
     * @return the chosen {@link SigningMode}, or {@code null} when the user
     *         pressed Back / closed the dialog / signed out
     */
    public SigningMode showAndWait(Window owner) {
        cancelled = true;
        openWorkflowRequested = false;
        remoteWorkflowRequested = false;
        sendForSignatureRequested = false;
        signOutRequested = false;
        backRequested = false;
        acceptInvitationRequested = false;
        selected.set(null);
        Stage dialog = new Stage(StageStyle.DECORATED);
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("SignFlow — Home");
        dialog.setMinWidth(760);
        dialog.setMinHeight(560);

        // ---- header ---------------------------------------------------------
        Label logo = new Label();
        logo.setGraphic(new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        logo.getStyleClass().add("app-logo");
        Label brand = new Label("SignFlow");
        brand.getStyleClass().add("app-name");
        Label tagline = new Label("PDF Digital Signing");
        tagline.getStyleClass().add("app-tagline");
        HBox brandRow = new HBox(10, logo, new VBox(1, brand, tagline));
        brandRow.setAlignment(Pos.CENTER_LEFT);

        Button themeToggle = ThemeManager.instance().createToggleButton();
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(brandRow, headerSpacer, themeToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("workflow-header");

        Label title = new Label("What would you like to do?");
        title.getStyleClass().add("workflow-title");
        Label subtitle = new Label("Choose one option. You can come back to"
                + " this screen at any time.");
        subtitle.getStyleClass().add("workflow-subtitle");
        VBox heading = new VBox(4, title, subtitle);
        heading.getStyleClass().add("workflow-heading");

        // ---- the four main actions: one click, no separate Continue --------
        VBox signHere = actionTile(FontAwesomeSolid.FILE_SIGNATURE,
                "Sign a document",
                "Sign a PDF yourself, here on this computer, with your"
                        + " USB token.",
                () -> {
                    selected.set(SigningMode.SINGLE);
                    cancelled = false;
                    dialog.close();
                });
        VBox sendForSignature = actionTile(FontAwesomeSolid.PAPER_PLANE,
                "Send for signature",
                "Choose a PDF and the people who must sign it. They are"
                        + " invited by email.",
                () -> {
                    remoteWorkflowRequested = true;
                    sendForSignatureRequested = true;
                    dialog.close();
                });
        VBox waiting = actionTile(FontAwesomeSolid.INBOX,
                "Documents to sign",
                "See the documents other people sent you, and the ones"
                        + " you sent.",
                () -> {
                    remoteWorkflowRequested = true;
                    dialog.close();
                });
        VBox invitation = actionTile(FontAwesomeSolid.ENVELOPE_OPEN,
                "Open an invitation",
                "You received an email asking you to sign? Paste its"
                        + " link here.",
                () -> {
                    acceptInvitationRequested = true;
                    dialog.close();
                });
        GridPane tiles = new GridPane();
        tiles.setHgap(16);
        tiles.setVgap(16);
        tiles.add(signHere, 0, 0);
        tiles.add(sendForSignature, 1, 0);
        tiles.add(waiting, 0, 1);
        tiles.add(invitation, 1, 1);
        for (int c = 0; c < 2; c++) {
            ColumnConstraints cc =
                    new ColumnConstraints();
            cc.setPercentWidth(50);
            cc.setHgrow(Priority.ALWAYS);
            tiles.getColumnConstraints().add(cc);
        }

        // ---- less common options: small links --------------------------------
        Label otherTitle = new Label("Other options");
        otherTitle.getStyleClass().add("workflow-card-meta");
        HBox otherLinks = new HBox(18);
        otherLinks.setAlignment(Pos.CENTER_LEFT);
        for (SigningMode mode : modes) {
            if (mode.isSingle()) {
                continue;
            }
            Hyperlink link = new Hyperlink(
                    mode.signerCount() + " people sign on this computer");
            link.setTooltip(new Tooltip("Each person signs in turn, on this"
                    + " computer, with their own USB token"));
            link.setOnAction(e -> {
                selected.set(mode);
                cancelled = false;
                dialog.close();
            });
            otherLinks.getChildren().add(link);
        }
        Hyperlink openWorkflow =
                new Hyperlink("Continue a signing PDF…");
        openWorkflow.setTooltip(new Tooltip(
                "Open a PDF that was sent to you to sign in turn"
                        + " (it carries its own list of signers)"));
        openWorkflow.setOnAction(e -> {
            openWorkflowRequested = true;
            dialog.close();
        });
        otherLinks.getChildren().add(openWorkflow);
        VBox other = new VBox(4, otherTitle, otherLinks);

        // ---- bottom navigation ----------------------------------------------
        // The home screen is the first screen of a session: leaving it closes
        // SignFlow, so the button says so.
        Button back = new Button("Quit");
        back.getStyleClass().add("btn-ghost");
        back.setCancelButton(true);
        back.setTooltip(new Tooltip("Close SignFlow (Esc)"));
        back.setOnAction(e -> {
            backRequested = true;
            dialog.close();
        });

        Button signOut = new Button("Sign out",
                new FontIcon(FontAwesomeSolid.SIGN_OUT_ALT));
        signOut.getStyleClass().add("btn-ghost");
        signOut.setTooltip(new Tooltip("Return to the sign-in screen"));
        signOut.setOnAction(e -> {
            signOutRequested = true;
            dialog.setUserData("SIGN_OUT");
            dialog.close();
        });

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, back, footerSpacer, signOut);
        footer.getStyleClass().add("workflow-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        // ---- root --------------------------------------------------------------
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox content = new VBox(22, header, heading, tiles, other, grow, footer);
        content.getStyleClass().add("workflow-root");
        content.setPadding(new Insets(24, 32, 20, 32));

        Scene scene = new Scene(content, 860, 600);
        ThemeManager.instance().attach(scene);
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);
        Platform.runLater(signHere::requestFocus);

        dialog.showAndWait();

        if ("SIGN_OUT".equals(dialog.getUserData())) {
            return null;
        }
        return cancelled ? null : selected.get();
    }

    /** Set when the user chose "Send for signature" (opens the new-workflow wizard). */
    private boolean sendForSignatureRequested;

    /** @return true when the user picked "Send for signature" on this dialog. */
    public boolean isSendForSignatureRequested() {
        return sendForSignatureRequested;
    }

    /**
     * One large, clickable action tile: icon, title and a one-sentence
     * explanation. Mouse click, Enter or Space runs {@code action}.
     */
    private VBox actionTile(FontAwesomeSolid icon, String titleText,
                            String descriptionText, Runnable action) {
        FontIcon glyph = new FontIcon(icon);
        glyph.getStyleClass().add("workflow-card-icon");
        Label title = new Label(titleText);
        title.getStyleClass().add("workflow-card-title");
        Label description = new Label(descriptionText);
        description.getStyleClass().add("workflow-card-description");
        description.setWrapText(true);
        Label go = new Label("Start", new FontIcon(FontAwesomeSolid.ARROW_RIGHT));
        go.setContentDisplay(ContentDisplay.RIGHT);
        go.getStyleClass().add("workflow-card-meta");

        VBox tile = new VBox(10, glyph, title, description, go);
        tile.getStyleClass().add("workflow-card");
        tile.setPadding(new Insets(20, 20, 16, 20));
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setMinHeight(150);
        tile.setCursor(Cursor.HAND);
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(AccessibleRole.BUTTON);
        tile.setAccessibleText(titleText + ". " + descriptionText);
        tile.setOnMouseClicked(e -> action.run());
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                e.consume();
                action.run();
            }
        });
        tile.focusedProperty().addListener((o, was, is) -> {
            tile.getStyleClass().remove("workflow-card--selected");
            if (is) {
                tile.getStyleClass().add("workflow-card--selected");
            }
        });
        return tile;
    }
}
