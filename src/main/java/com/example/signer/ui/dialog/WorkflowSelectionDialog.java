package com.example.signer.ui.dialog;

import com.example.signer.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.AccessibleRole;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import com.example.signer.ui.support.UiSupport;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

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

/**
 * Home screen ("What would you like to do?"), shown after sign-in. Two
 * large one-click actions cover everything SignFlow does — sign a PDF here
 * on this computer, or work through the documents held on the server — and
 * continuing a signing PDF that already carries its own workflow is a small
 * link underneath.
 *
 * <p>Styling lives in {@code application.css} ({@code .workflow-*} classes)
 * and follows the shadcn-style token design system, including dark mode via
 * {@link ThemeManager}.</p>
 */
public final class WorkflowSelectionDialog {

    /** Set when the user chose to sign a PDF on this computer. */
    private boolean signHereRequested;

    /** Set when the user chose to open an existing (portable) workflow PDF. */
    private boolean openWorkflowRequested;

    /** Set when the user chose “My documents” on this dialog. */
    private boolean remoteWorkflowRequested;

    /** Set when the user pressed Back (as opposed to closing the window). */
    private boolean backRequested;

    /** Set when the user chose “Sign out” on this dialog. */
    private boolean signOutRequested;

    /** @return true when the user picked “Sign a document” on this dialog. */
    public boolean isSignHereRequested() {
        return signHereRequested;
    }

    /** @return true when the user picked “Continue a signing PDF…”. */
    public boolean isOpenWorkflowRequested() {
        return openWorkflowRequested;
    }

    /** @return true when the user picked “My documents” on this dialog. */
    public boolean isRemoteWorkflowRequested() {
        return remoteWorkflowRequested;
    }

    /** @return true when the user pressed Back on this dialog. */
    public boolean isBackRequested() {
        return backRequested;
    }

    /** @return true when the user chose “Sign out” on this dialog. */
    public boolean isSignOutRequested() {
        return signOutRequested;
    }

    /**
     * Shows the modal home screen. The caller reads which action was picked
     * from the {@code isXxxRequested} accessors; none of them being set
     * means the user pressed Quit, closed the window or signed out.
     */
    public void showAndWait(Window owner) {
        signHereRequested = false;
        openWorkflowRequested = false;
        remoteWorkflowRequested = false;
        signOutRequested = false;
        backRequested = false;
        Stage dialog = new Stage(StageStyle.DECORATED);
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("SignFlow — Home");
        dialog.setMinWidth(760);
        dialog.setMinHeight(520);

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

        // ---- the two main actions: one click, no separate Continue ---------
        VBox signHere = actionTile(FontAwesomeSolid.FILE_SIGNATURE,
                "Sign a document",
                "Sign a PDF on this computer with your USB token.",
                () -> {
                    signHereRequested = true;
                    dialog.close();
                });
        VBox myDocuments = actionTile(FontAwesomeSolid.INBOX,
                "My documents",
                "Upload a PDF to SignFlow, or sign one that is waiting"
                        + " there for you.",
                () -> {
                    remoteWorkflowRequested = true;
                    dialog.close();
                });
        GridPane tiles = new GridPane();
        tiles.setHgap(16);
        tiles.setVgap(16);
        tiles.add(signHere, 0, 0);
        tiles.add(myDocuments, 1, 0);
        for (int c = 0; c < 2; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            cc.setHgrow(Priority.ALWAYS);
            tiles.getColumnConstraints().add(cc);
        }

        // ---- less common options: small links --------------------------------
        Label otherTitle = new Label("Other options");
        otherTitle.getStyleClass().add("workflow-card-meta");
        HBox otherLinks = new HBox(18);
        otherLinks.setAlignment(Pos.CENTER_LEFT);
        Hyperlink openWorkflow = new Hyperlink("Continue a signing PDF…");
        openWorkflow.setTooltip(new Tooltip(
                "Open a PDF that already carries a SignFlow workflow"));
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

        Scene scene = new Scene(content, 860, 560);
        ThemeManager.instance().attach(scene);
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);
        Platform.runLater(signHere::requestFocus);

        dialog.showAndWait();
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
