package com.example.signer.ui;

import com.example.signer.SignerApplication;
import com.example.signer.remote.WorkflowApiClient;
import com.example.signer.ui.support.UiSupport;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

import java.util.List;

/**
 * JavaFX entry point. Its only job is the application's life cycle:
 *
 * <ol>
 *   <li>{@link #init()}: make sure only one SignFlow runs
 *       ({@link SingleInstanceGuard}), then start Spring;</li>
 *   <li>{@link #start(Stage)}: report start-up problems, then hand over to
 *       {@link AppNavigator} — the invitation flow when opened from a
 *       {@code signflow://} link, otherwise sign-in and the home screen;</li>
 *   <li>{@link #stop()}: release everything.</li>
 * </ol>
 *
 * <p>Screens never touch Spring: they get their services from
 * {@link DesktopServices}, resolved once here.
 */
public class SignerFxApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(SignerFxApplication.class);

    private final SingleInstanceGuard instanceGuard = new SingleInstanceGuard();
    private SingleInstanceGuard.Outcome instanceOutcome;
    private ConfigurableApplicationContext context;
    /** Configuration error that prevented Spring from starting, shown in start(). */
    private String startupError;
    /** Token from a {@code signflow://invitation/<token>} launch argument, or null. */
    private String launchInvitationToken;

    /** Hidden owner of every window; raised as a fallback on activation. */
    private Stage stage;
    private AppNavigator navigator;

    @Override
    public void init() {
        // An exception escaping an event handler would otherwise die as an
        // anonymous console trace; log every one of them.
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.error("Uncaught exception on thread {}", thread.getName(), throwable));

        // Read the link BEFORE the single-instance check: a second launch
        // forwards it to the running copy instead of opening it itself.
        launchInvitationToken = InvitationLinks.extractInvitationToken(getParameters().getRaw());
        if (launchInvitationToken != null) {
            log.info("Launched from an invitation link");
        }
        instanceOutcome = instanceGuard.acquire(launchInvitationToken);
        switch (instanceOutcome) {
            case HANDED_OFF -> Platform.exit();
            case OTHER_UNRESPONSIVE -> {
                // start() asks the user whether to replace the stuck copy.
            }
            case FIRST_INSTANCE -> bootSpring();
        }
    }

    @Override
    public void start(Stage primaryStage) {
        if (instanceOutcome == SingleInstanceGuard.Outcome.HANDED_OFF) {
            return;
        }
        if (instanceOutcome == SingleInstanceGuard.Outcome.OTHER_UNRESPONSIVE
                && !replaceStuckCopy()) {
            Platform.exit();
            return;
        }
        if (startupError != null || context == null) {
            showStartupError();
            Platform.exit();
            return;
        }

        log.info("Starting JavaFX stage");
        stage = primaryStage;
        stage.setTitle("SignFlow");
        UiSupport.applyAppIcon(stage);
        navigator = new AppNavigator(DesktopServices.from(context), stage);
        instanceGuard.startActivationListener(this::raiseForActivation);

        if (launchInvitationToken != null) {
            // Invited signer: no SignFlow account, so no sign-in. They get the
            // invitation, a guest session for that one task, and the app
            // closes when they are done.
            log.info("Started from an invitation link — guest signing, no sign-in");
            String token = launchInvitationToken;
            launchInvitationToken = null;
            navigator.acceptInvitation(stage, token);
        } else if (navigator.signIn()) {
            navigator.runHome();
        }
        Platform.exit();
    }

    @Override
    public void stop() {
        log.info("Shutting down application");
        instanceGuard.close();
        if (context != null) {
            context.close();
        }
    }

    /** Boots Spring: desktop profile, no web server, no database. */
    private void bootSpring() {
        log.info("Initializing Spring application context");
        SpringApplication app = new SpringApplication(SignerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("desktop");
        try {
            context = app.run(getParameters().getRaw().toArray(new String[0]));
        } catch (RuntimeException e) {
            // Fail fast and visibly: an unsafe production configuration stops
            // the app with the reason instead of falling back to dev settings.
            startupError = rootMessage(e);
            log.error("SignFlow cannot start: {}", startupError, e);
            return;
        }
        // Plain http to a non-loopback server is allowed only in dev.
        WorkflowApiClient.allowInsecureTransportForDevelopment(
                context.getEnvironment().acceptsProfiles(Profiles.of("dev")));
        log.info("Spring application context initialized");
    }

    /**
     * Another copy holds the lock but does not answer: offer to restart.
     *
     * @return true when this process took over and Spring was started
     */
    private boolean replaceStuckCopy() {
        ButtonType restart = new ButtonType("Restart SignFlow", ButtonBar.ButtonData.OK_DONE);
        Alert ask = new Alert(Alert.AlertType.WARNING,
                "Another copy of SignFlow is running but isn't responding, so its"
                        + " window can't be shown.\n\nRestart SignFlow? The stuck copy"
                        + " will be closed (nothing is signed or sent by this).",
                restart, ButtonType.CANCEL);
        ask.setTitle("SignFlow is already running");
        ask.setHeaderText("SignFlow is already running");
        if (ask.showAndWait().orElse(ButtonType.CANCEL) != restart) {
            return false;
        }
        if (!instanceGuard.replaceUnresponsiveInstance()) {
            Alert fail = new Alert(Alert.AlertType.ERROR,
                    "The other copy could not be closed. Close it from the Task Manager"
                            + " (Java Platform SE binary), then start SignFlow again.");
            fail.setHeaderText("SignFlow could not restart");
            fail.showAndWait();
            return false;
        }
        bootSpring();
        return true;
    }

    private void showStartupError() {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("SignFlow cannot start");
        alert.setHeaderText("SignFlow cannot start with this configuration");
        TextArea details = new TextArea(startupError != null ? startupError
                : "The application context did not start. See the log for details.");
        details.setEditable(false);
        details.setWrapText(true);
        alert.getDialogPane().setContent(details);
        alert.showAndWait();
    }

    /**
     * FX thread, on a second launch: bring the visible screen to the front
     * and, when that launch came from an invitation link, open it.
     *
     * @return true when a window was raised (or the invitation will open)
     */
    private boolean raiseForActivation(String forwardedToken) {
        // Dialogs are created after their owners, so the LAST showing stage
        // is the screen the user is looking at.
        Stage target = null;
        List<Window> open = Window.getWindows();
        for (int i = open.size() - 1; i >= 0; i--) {
            if (open.get(i) instanceof Stage s && s.isShowing()) {
                target = s;
                break;
            }
        }
        if (target == null) {
            target = stage;
        }
        boolean raised = target != null && (target.isShowing() || forwardedToken != null);
        if (target != null) {
            // Windows refuses focus to background processes (toFront() only
            // flashes the taskbar); a brief always-on-top round trip works.
            Stage window = target;
            window.setIconified(false);
            window.setAlwaysOnTop(true);
            window.toFront();
            window.requestFocus();
            PauseTransition drop = new PauseTransition(Duration.millis(400));
            drop.setOnFinished(ev -> window.setAlwaysOnTop(false));
            drop.play();
            log.info("Activated by a second launch attempt (raised: {})", window.getTitle());
        }
        if (forwardedToken != null) {
            log.info("Second launch forwarded an invitation link");
            // Next pulse: the flow waits for the user, while the second launch
            // must get its answer now.
            Window owner = target != null ? target : stage;
            Platform.runLater(() -> navigator.acceptInvitation(owner, forwardedToken));
        }
        return raised;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        String message = e.getMessage();
        while (t != null) {
            if (t instanceof IllegalStateException && t.getMessage() != null) {
                message = t.getMessage();
            }
            t = t.getCause();
        }
        return message == null ? e.toString() : message;
    }
}
