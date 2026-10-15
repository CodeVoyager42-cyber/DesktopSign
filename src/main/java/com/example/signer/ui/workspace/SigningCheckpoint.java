package com.example.signer.ui.workspace;

import com.example.signer.ui.ThemeManager;
import com.example.signer.ui.support.UiSupport;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * The "Ready to Sign" checkpoint shown by every local signing screen right
 * before the card signs: the real stamp preview, a summary of what will be
 * signed, a warning, and an "I have reviewed…" box that unlocks Sign.
 */
final class SigningCheckpoint {

    /** One line of the summary ("Page" → "2"). A null value shows "Not specified". */
    record Row(String key, String value) {
    }

    private SigningCheckpoint() {
    }

    /** @return true when the user confirmed and clicked Sign Document */
    static boolean confirm(Window owner, Image stampPreview, List<Row> rows) {
        Stage dialog = new Stage(StageStyle.DECORATED);
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Ready to Sign");

        ImageView preview = new ImageView(stampPreview);
        preview.setFitWidth(400);
        preview.setPreserveRatio(true);
        preview.getStyleClass().add("mws-checkpoint-preview");

        VBox summary = new VBox(6);
        for (Row row : rows) {
            summary.getChildren().add(row(row.key(), row.value()));
        }
        summary.getStyleClass().add("mws-checkpoint-summary");

        Label warning = new Label("Once signed, this document revision cannot be "
                + "modified without affecting the signature.");
        warning.getStyleClass().add("mws-checkpoint-warning");
        warning.setWrapText(true);

        CheckBox reviewed = new CheckBox(
                "I have reviewed the document and my signature placement.");
        reviewed.getStyleClass().add("mws-checkpoint-confirm");
        Button signButton = new Button("Sign Document", new FontIcon(FontAwesomeSolid.FILE_SIGNATURE));
        signButton.getStyleClass().add("btn-primary");
        signButton.setDisable(true);
        reviewed.selectedProperty().addListener((o, was, now) -> signButton.setDisable(!now));
        boolean[] confirmed = {false};
        signButton.setOnAction(e -> {
            confirmed[0] = true;
            dialog.close();
        });
        Button backButton = new Button("Back");
        backButton.getStyleClass().add("btn-ghost");
        backButton.setCancelButton(true);
        backButton.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(10, backButton, signButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(14, preview, summary, warning, reviewed, buttons);
        content.setFillWidth(true);
        VBox.setVgrow(summary, Priority.ALWAYS);
        content.getStyleClass().add("mws-checkpoint");
        content.setPadding(new Insets(18));
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(content,
                Math.max(320, Math.min(460, screen.getWidth() - 48)),
                Math.max(240, Math.min(560, screen.getHeight() - 48)));
        ThemeManager.instance().attach(scene);
        // The stamp preview shrinks with the dialog on small screens.
        preview.fitWidthProperty().bind(Bindings.min(400.0, scene.widthProperty().subtract(56)));
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);
        dialog.showAndWait();
        return confirmed[0];
    }

    private static HBox row(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("mws-row-key");
        Label valueLabel = new Label(value == null || value.isBlank() ? "Not specified" : value);
        valueLabel.getStyleClass().add("mws-row-value");
        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);
        HBox box = new HBox(10, keyLabel, valueLabel);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }
}
