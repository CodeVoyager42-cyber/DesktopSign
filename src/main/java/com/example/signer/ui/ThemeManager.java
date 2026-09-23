package com.example.signer.ui;

import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Central owner of the application theme (light / dark).
 *
 * <p>Dark mode is implemented as a CSS token overlay: the dark stylesheet
 * redefines only the color-related looked-up tokens on {@code .root}, so
 * every rule in {@code application.css} that references them re-resolves
 * automatically. Layout-affecting rules are never overridden.</p>
 *
 * <p>The choice is persisted in the Java user preferences store under the
 * key {@code signer.theme}. For tests and demos it can be forced with the
 * {@code signer.theme} system property ({@code light} / {@code dark});
 * the property wins over the stored preference.</p>
 */
public final class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    /** Base stylesheet present in every scene, light or dark. */
    private static final String BASE_CSS = "/ui/application.css";
    /** Dark token overlay added after the base sheet when dark mode is on. */
    private static final String DARK_CSS = "/ui/application-dark.css";
    /** Preferences / system-property key for the theme choice. */
    private static final String THEME_KEY = "signer.theme";

    private static final ThemeManager INSTANCE = new ThemeManager();

    private final BooleanProperty dark = new SimpleBooleanProperty(this, "dark", false);
    private final Preferences prefs = Preferences.userNodeForPackage(ThemeManager.class);

    private ThemeManager() {
        String forced = System.getProperty(THEME_KEY);
        if (forced != null) {
            dark.set("dark".equalsIgnoreCase(forced.trim()));
        } else {
            dark.set(prefs.get(THEME_KEY, "light").equalsIgnoreCase("dark"));
        }
    }

    public static ThemeManager instance() {
        return INSTANCE;
    }

    /** Observable view of the dark flag, for binding UI state. */
    public BooleanProperty darkProperty() {
        return dark;
    }

    public boolean isDark() {
        return dark.get();
    }

    public void setDark(boolean value) {
        dark.set(value);
    }

    /** Flips the theme and persists the choice. */
    public void toggle() {
        setDark(!isDark());
        prefs.put(THEME_KEY, isDark() ? "dark" : "light");
    }

    /**
     * Applies the base stylesheet (and the dark overlay when active) to a
     * scene, and keeps the scene in sync when the theme changes at runtime.
     */
    public void attach(Scene scene) {
        String base = getClass().getResource(BASE_CSS).toExternalForm();
        String overlay = getClass().getResource(DARK_CSS).toExternalForm();

        ChangeListener<Boolean> reapply = (obs, was, is) -> {
            Runnable task = () -> applyStylesheets(scene, base, overlay);
            if (Platform.isFxApplicationThread()) {
                task.run();
            } else {
                Platform.runLater(task);
            }
        };
        dark.addListener(reapply);
        applyStylesheets(scene, base, overlay);
    }

    private static void applyStylesheets(Scene scene, String base, String overlay) {
        scene.getStylesheets().setAll(base);
        if (instance().dark.get()) {
            scene.getStylesheets().add(overlay);
            log.info("Applied dark theme (overlay: {})", overlay);
        } else {
            log.info("Applied light theme");
        }
    }

    /**
     * A header toggle button that flips the theme; its glyph tracks the
     * current theme (sun shown while dark, moon while light).
     */
    public Button createToggleButton() {
        Button toggle = new Button();
        toggle.getStyleClass().addAll("theme-toggle", "nav-btn");
        toggle.setMnemonicParsing(false);
        toggle.setTooltip(new Tooltip("Switch between light and dark mode"));
        toggle.setFocusTraversable(false);
        Runnable sync = () -> toggle.setGraphic(
                new FontIcon(dark.get() ? FontAwesomeSolid.SUN : FontAwesomeSolid.MOON));
        sync.run();
        dark.addListener(obs -> sync.run());
        toggle.setOnAction(e -> toggle());
        return toggle;
    }
}
