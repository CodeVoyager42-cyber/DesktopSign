package com.example.signer.ui.kit;

import javafx.scene.control.ContentDisplay;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Small building blocks shared by the signing screens (invitation, confirm,
 * progress, success, signature properties) so every screen has the same
 * hierarchy: a navy step rail on the left, one card in the middle, one
 * obvious primary action. Pure presentation — no services, no I/O.
 *
 * <p>Styles: {@code .sf-*} classes in {@code application.css}, built only
 * from the {@code -sig-*} theme tokens (light and dark mode).
 */
public final class SignFlowUi {

    /** Visual tone of a state or chip. */
    public enum Tone {
        OK("sf-tone-ok"), INFO("sf-tone-info"), WARN("sf-tone-warn"), ERROR("sf-tone-error");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("MMMM d, yyyy 'at' HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private SignFlowUi() {
    }

    /** "October 9, 2026 at 14:30" in the local time zone; "—" when unknown. */
    public static String dateTime(Instant instant) {
        return instant == null ? "—" : DATE_TIME.format(instant);
    }

    /** Parses an ISO-8601 instant string; {@code null} when absent or malformed. */
    public static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Window shell: the left step rail and a scrollable, centered content
     * column that keeps its maximum width on large windows and scrolls on
     * small ones (the primary action can never be pushed off screen).
     */
    public static BorderPane shell(VBox rail, Node content) {
        StackPane center = new StackPane(content);
        center.setAlignment(Pos.TOP_CENTER);
        center.setPadding(new Insets(28, 28, 28, 28));
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sf-scroll");
        BorderPane root = new BorderPane();
        root.getStyleClass().add("sf-window");
        root.setLeft(rail);
        root.setCenter(scroll);
        return root;
    }

    /** The navy rail: brand + the numbered steps, the current one highlighted. */
    public static VBox stepRail(List<String> steps, int current) {
        Label brand = new Label("SignFlow", new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        brand.getStyleClass().add("sf-rail-brand");
        VBox rail = new VBox(6, brand);
        rail.getStyleClass().add("sf-rail");
        for (int i = 0; i < steps.size(); i++) {
            Label number = new Label(i < current ? "" : String.valueOf(i + 1));
            if (i < current) {
                number.setGraphic(new FontIcon(FontAwesomeSolid.CHECK));
            }
            number.getStyleClass().add("sf-step-number");
            Label text = new Label(steps.get(i));
            text.getStyleClass().add("sf-step-text");
            HBox item = new HBox(10, number, text);
            item.setAlignment(Pos.CENTER_LEFT);
            item.getStyleClass().add("sf-step");
            if (i == current) {
                item.getStyleClass().add("sf-step--current");
            } else if (i < current) {
                item.getStyleClass().add("sf-step--done");
            }
            rail.getChildren().add(item);
        }
        return rail;
    }

    /** The content card (max 640 px wide, grows down). */
    public static VBox card(Node... children) {
        VBox card = new VBox(16, children);
        card.getStyleClass().add("sf-card");
        card.setMaxWidth(640);
        card.setPadding(new Insets(24));
        return card;
    }

    public static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("sf-title");
        label.setWrapText(true);
        return label;
    }

    public static Label text(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("sf-text");
        label.setWrapText(true);
        return label;
    }

    /** A rounded status pill, e.g. "Invitation ready". */
    public static Label chip(String text, Tone tone) {
        Label chip = new Label(text);
        chip.getStyleClass().addAll("sf-chip", tone.styleClass());
        return chip;
    }

    /** Large round icon used at the top of state screens. */
    public static StackPane stateIcon(Ikon icon, Tone tone) {
        FontIcon glyph = new FontIcon(icon);
        glyph.getStyleClass().add("sf-state-glyph");
        StackPane circle = new StackPane(glyph);
        circle.getStyleClass().addAll("sf-state-icon", tone.styleClass());
        circle.setMinSize(64, 64);
        circle.setMaxSize(64, 64);
        return circle;
    }

    /**
     * A centered state screen: icon, title, explanation and its actions
     * (first action = primary).
     */
    public static VBox statePanel(Ikon icon, Tone tone, String titleText,
                                  String explanation, Button... actions) {
        Label t = title(titleText);
        t.setAlignment(Pos.CENTER);
        Label x = text(explanation);
        x.setAlignment(Pos.CENTER);
        x.setStyle("-fx-text-alignment: center;");
        HBox buttons = new HBox(10, actions);
        buttons.setAlignment(Pos.CENTER);
        VBox panel = card(stateIcon(icon, tone), t, x, buttons);
        panel.setAlignment(Pos.TOP_CENTER);
        return panel;
    }

    /** Label/value grid ("Requested by  alice"); null/blank values are skipped. */
    public static GridPane details(String... labelValuePairs) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("sf-details");
        grid.setHgap(18);
        grid.setVgap(10);
        ColumnConstraints keyCol = new ColumnConstraints();
        keyCol.setMinWidth(130);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(keyCol, valueCol);
        int row = 0;
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            String value = labelValuePairs[i + 1];
            if (value == null || value.isBlank()) {
                continue;
            }
            Label key = new Label(labelValuePairs[i]);
            key.getStyleClass().add("sf-detail-key");
            Label val = new Label(value);
            val.getStyleClass().add("sf-detail-value");
            val.setWrapText(true);
            grid.addRow(row++, key, val);
        }
        return grid;
    }

    /** Info box with an icon (security notes, explanations). */
    public static HBox note(Ikon icon, Tone tone, String text) {
        FontIcon glyph = new FontIcon(icon);
        glyph.getStyleClass().add("sf-note-glyph");
        Label label = text(text);
        HBox.setHgrow(label, Priority.ALWAYS);
        HBox box = new HBox(12, glyph, label);
        box.setAlignment(Pos.TOP_LEFT);
        box.getStyleClass().addAll("sf-note", tone.styleClass());
        return box;
    }

    public static Button primary(String text, Ikon trailingIcon) {
        Button button = new Button(text);
        if (trailingIcon != null) {
            button.setGraphic(new FontIcon(trailingIcon));
            button.setContentDisplay(ContentDisplay.RIGHT);
        }
        button.getStyleClass().addAll("btn-primary", "sf-primary");
        button.setDefaultButton(true);
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    public static Button secondary(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("btn-secondary");
        return button;
    }

    public static Region grow() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        VBox.setVgrow(region, Priority.ALWAYS);
        return region;
    }
}
