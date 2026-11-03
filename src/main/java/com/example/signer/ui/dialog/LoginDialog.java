package com.example.signer.ui.dialog;

import com.example.signer.ui.ThemeManager;
import com.example.signer.config.LoginProperties;
import com.example.signer.ui.support.UiSupport;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Start-up login gate: a simple username/password dialog shown before the
 * main UI. Credentials come from {@link LoginProperties} (defaults
 * {@code ISICOD}/{@code ISICOD}, overridable via configuration).
 *
 * <p>{@link #showAndWait()} returns {@code true} when the user signed in
 * successfully and {@code false} when the dialog was dismissed.
 */
public final class LoginDialog {

    private final LoginProperties loginProperties;

    public LoginDialog(LoginProperties loginProperties) {
        this.loginProperties = loginProperties;
    }

    /** Shows the modal login dialog; blocks until signed in or dismissed. */
    public boolean showAndWait() {
        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Sign in");

        FontIcon logoGlyph = new FontIcon(FontAwesomeSolid.FILE_SIGNATURE);
        Label logo = new Label();
        logo.setGraphic(logoGlyph);
        logo.getStyleClass().add("app-logo");
        Label brand = new Label("SignFlow");
        brand.getStyleClass().add("login-brand");
        Label tagline = new Label("PDF Digital Signing");
        tagline.getStyleClass().add("login-tagline");

        Label prompt = new Label("Sign in to continue");
        prompt.getStyleClass().add("login-prompt");

        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        usernameField.getStyleClass().add("login-field");
        usernameField.setTooltip(new Tooltip("Your account username"));

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.getStyleClass().add("login-field");
        passwordField.setTooltip(new Tooltip("Your account password"));

        Label error = new Label();
        error.getStyleClass().add("login-error");
        error.setManaged(false);
        error.setVisible(false);

        Button signIn = new Button("Sign in");
        signIn.getStyleClass().add("btn-primary");
        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);

        Button cancel = new Button("Exit");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setCancelButton(true);
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> dialog.close());

        Runnable attempt = () -> {
            String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
            String password = passwordField.getText() == null ? "" : passwordField.getText();
            if (loginProperties.matches(username, password)) {
                dialog.setUserData(Boolean.TRUE);
                dialog.close();
                return;
            }
            error.setText("Incorrect username or password.");
            error.setManaged(true);
            error.setVisible(true);
            passwordField.clear();
            passwordField.requestFocus();
        };
        signIn.setOnAction(e -> attempt.run());

        VBox card = new VBox(10,
                new HBox(10, logo, new VBox(1, brand, tagline)),
                prompt,
                usernameField,
                passwordField,
                error,
                signIn,
                cancel);
        card.getStyleClass().add("login-card");
        card.setSpacing(10);
        card.setPadding(new Insets(24));
        ((HBox) card.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(card);
        root.getStyleClass().add("login-backdrop");
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));

        Scene scene = new Scene(root, 400, 480);
        ThemeManager.instance().attach(scene);
        dialog.setScene(scene);
        UiSupport.applyAppIcon(dialog);

        dialog.setOnShown(e -> usernameField.requestFocus());
        dialog.showAndWait();
        return Boolean.TRUE.equals(dialog.getUserData());
    }
}
