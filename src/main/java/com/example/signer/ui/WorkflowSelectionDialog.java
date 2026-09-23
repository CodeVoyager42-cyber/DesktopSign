package com.example.signer.ui;

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
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Choose Signing Workflow" screen, shown after successful sign-in and before
 * any signing UI.
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
        Stage dialog = new Stage(StageStyle.DECORATED);
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Choose Signing Workflow");
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

        Label title = new Label("Choose Signing Workflow");
        title.getStyleClass().add("workflow-title");
        Label subtitle = new Label("Select the number of people who will sign this document.");
        subtitle.getStyleClass().add("workflow-subtitle");
        VBox heading = new VBox(4, title, subtitle);
        heading.getStyleClass().add("workflow-heading");

        Button themeToggle = ThemeManager.instance().createToggleButton();
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(brandRow, headerSpacer, themeToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("workflow-header");

        // ---- cards ------------------------------------------------------------
        Map<SigningMode, VBox> cardsByMode = new LinkedHashMap<>();
        List<VBox> cardOrder = new java.util.ArrayList<>();
        for (SigningMode mode : modes) {
            VBox card = buildCard(dialog, mode, cardsByMode);
            cardsByMode.put(mode, card);
            cardOrder.add(card);
        }
        HBox cardsRow = new HBox(16, cardsByMode.values().toArray(new VBox[0]));
        cardsRow.getStyleClass().add("workflow-cards");
        cardsRow.setAlignment(Pos.CENTER);

        // ---- bottom navigation --------------------------------------------------
        Button back = new Button("Back");
        back.getStyleClass().add("btn-ghost");
        back.setCancelButton(true);
        back.setTooltip(new Tooltip("Return to the previous screen (Esc)"));
        back.setOnAction(e -> dialog.close());

        Button signOut = new Button("Sign out");
        signOut.getStyleClass().add("btn-ghost");
        signOut.setTooltip(new Tooltip("Return to the sign-in screen"));
        signOut.setOnAction(e -> {
            cancelled = true;
            selected.set(null);
            dialog.setUserData("SIGN_OUT");
            dialog.close();
        });

        Button continueButton = new Button("Continue", new FontIcon(FontAwesomeSolid.ARROW_RIGHT));
        continueButton.getStyleClass().add("btn-primary");
        continueButton.setDisable(true);
        continueButton.setDefaultButton(true);
        continueButton.setTooltip(new Tooltip("Open the selected signing workflow (Enter)"));
        selected.addListener((obs, was, is) -> continueButton.setDisable(is == null));
        continueButton.setOnAction(e -> {
            if (selected.get() != null) {
                cancelled = false;
                dialog.close();
            }
        });

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, back, signOut, footerSpacer, continueButton);
        footer.getStyleClass().add("workflow-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        // ---- root ------------------------------------------------------------------
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox content = new VBox(28, heading, cardsRow, grow, footer);
        content.getStyleClass().add("workflow-root");
        content.setPadding(new Insets(28, 32, 24, 32));

        Scene scene = new Scene(content, 860, 540);
        ThemeManager.instance().attach(scene);

        // Arrow keys walk the cards like a radio group; from the cards, Enter
        // already selects via the per-card handler and then re-pressing Enter
        // activates the default Continue button.
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            KeyCode code = e.getCode();
            if (code != KeyCode.LEFT && code != KeyCode.RIGHT) {
                return;
            }
            int index = selected.get() == null ? -1 : modes.indexOf(selected.get());
            if (index < 0) {
                // No selection yet: an arrow in either direction selects the
                // first card, then further presses walk the row.
                index = 0;
            } else if (code == KeyCode.RIGHT && index < modes.size() - 1) {
                index++;
            } else if (code == KeyCode.LEFT && index > 0) {
                index--;
            }
            e.consume();
            cardsByMode.get(modes.get(index)).requestFocus();
            selectMode(cardsByMode, modes.get(index));
        });
        dialog.setScene(scene);

        dialog.showAndWait();

        if ("SIGN_OUT".equals(dialog.getUserData())) {
            return null;
        }
        return cancelled ? null : selected.get();
    }

    /** Builds one selectable workflow card with icon, title, description, meta. */
    private VBox buildCard(Stage dialog,
                           SigningMode mode,
                           Map<SigningMode, VBox> cardsByMode) {
        FontIcon glyph = new FontIcon(switch (mode) {
            case SINGLE -> FontAwesomeSolid.FILE_SIGNATURE;
            case TWO_SIGNERS -> FontAwesomeSolid.USER_FRIENDS;
            default -> FontAwesomeSolid.USERS;
        });
        glyph.getStyleClass().add("workflow-card-icon");

        Label title = new Label(switch (mode) {
            case SINGLE -> "Single Signer";
            case TWO_SIGNERS -> "Two Signers";
            default -> mode.signerCount() + " Signers";
        });
        title.getStyleClass().add("workflow-card-title");

        Label description = new Label(switch (mode) {
            case SINGLE -> "Sign a document with one digital signature.";
            case TWO_SIGNERS -> "Allow two people to digitally sign the same document.";
            default -> "Allow " + mode.signerCount()
                    + " people to digitally sign the same document.";
        });
        description.getStyleClass().add("workflow-card-description");
        description.setWrapText(true);

        Label meta = new Label(mode.signerCount() + " signer"
                + (mode.signerCount() == 1 ? "" : "s") + "  ·  "
                + (mode.isSingle() ? "Standard document signing"
                : "Multi-party document signing"));
        meta.getStyleClass().add("workflow-card-meta");

        Label check = new Label();
        check.setGraphic(new FontIcon(FontAwesomeSolid.CHECK));
        check.getStyleClass().add("workflow-card-check");
        check.setVisible(false);
        check.setManaged(false);

        VBox text = new VBox(6, title, description, meta);
        VBox card = new VBox(14, glyph, text, check);
        card.getStyleClass().add("workflow-card");
        card.setPadding(new Insets(22, 20, 18, 20));
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setCursor(Cursor.HAND);
        card.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        card.setAccessibleText(title.getText() + ". " + description.getText());

        card.setOnMouseClicked(e -> selectMode(cardsByMode, mode));
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                e.consume();
                selectMode(cardsByMode, mode);
            }
        });
        card.setFocusTraversable(true);
        return card;
    }

    /** Applies single selection: highlight + checkmark on the chosen card only. */
    private void selectMode(Map<SigningMode, VBox> cardsByMode, SigningMode mode) {
        selected.set(mode);
        cardsByMode.forEach((m, card) -> {
            boolean active = m == mode;
            card.getStyleClass().remove("workflow-card--selected");
            if (active) {
                card.getStyleClass().add("workflow-card--selected");
            }
            Label check = (Label) card.getChildren().get(card.getChildren().size() - 1);
            check.setVisible(active);
            check.setManaged(active);
        });
    }
}
