package com.example.signer.remote;

import com.example.signer.ui.kit.SignFlowUi;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Small layout helpers shared by the remote screens. */
final class RemoteScreens {

    private RemoteScreens() {
    }

    static VBox loadingCard(String text) {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(36, 36);
        VBox card = SignFlowUi.card(indicator,
                SignFlowUi.text(text));
        card.setAlignment(Pos.CENTER);
        return card;
    }

    /** Centers a card and lets it scroll on small windows (actions stay reachable). */
    static ScrollPane centered(Node card) {
        StackPane holder = new StackPane(card);
        holder.setAlignment(Pos.TOP_CENTER);
        holder.setPadding(new Insets(8));
        ScrollPane scroll = new ScrollPane(holder);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sf-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("sf-detail-value");
        return label;
    }
}
