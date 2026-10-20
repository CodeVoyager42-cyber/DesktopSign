package com.example.signer.ui;

import com.example.signer.SignerApplication;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.ui.support.UiSupport;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
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
    /** Second-launch requests, queued until the window exists. */
    private final ActivationQueue activations = new ActivationQueue(System::currentTimeMillis);
    private SingleInstanceGuard.Outcome instanceOutcome;
    private ConfigurableApplicationContext context;
    /** Configuration error that prevented Spring from starting, shown in start(). */
    private String startupError;
    /** The link SignFlow was started with (invitation or website launch), or null. */
    private InvitationLinks.LaunchArgument launchArgument;

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
        launchArgument = InvitationLinks.parse(getParameters().getRaw());
        if (launchArgument != null) {
            log.info("Launched from a {} link", launchArgument.kind() == InvitationLinks
                    .LaunchArgument.Kind.LAUNCH ? "website launch" : "invitation");
        }
        instanceOutcome = instanceGuard.acquire(launchArgument);
        switch (instanceOutcome) {
            case HANDED_OFF -> Platform.exit();
            case ALREADY_RUNNING_UNVERIFIED, OTHER_UNRESPONSIVE -> {
                // start() explains; nothing was forwarded, nothing is closed.
            }
            case FIRST_INSTANCE -> {
                // Listen at once (before Spring starts) so a second click
                // during start-up is queued, never mistaken for a dead copy.
                activations.markOpened(launchArgument);
                instanceGuard.startActivationListener(activations);
                bootSpring();
            }
            case STANDALONE -> bootSpring();   // no hand-off at all
        }
    }

    @Override
    public void start(Stage primaryStage) {
        if (instanceOutcome == SingleInstanceGuard.Outcome.HANDED_OFF) {
            return;
        }
        if (instanceOutcome == SingleInstanceGuard.Outcome.ALREADY_RUNNING_UNVERIFIED
                || instanceOutcome == SingleInstanceGuard.Outcome.OTHER_UNRESPONSIVE) {
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    alreadyRunningMessage(instanceOutcome, launchArgument));
            info.setTitle("SignFlow is already running");
            info.setHeaderText("SignFlow is already running");
            info.showAndWait();
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
        // From now on second launches reach the window; queued ones open
        // after the current step (each on its own FX pulse).
        for (InvitationLinks.LaunchArgument queued
                : activations.ready(a -> Platform.runLater(() -> raiseForActivation(a)))) {
            Platform.runLater(() -> raiseForActivation(queued));
        }
        if (launchArgument != null
                && launchArgument.kind() == InvitationLinks.LaunchArgument.Kind.LAUNCH) {
            // Started from the website: the one-time code opens exactly one
            // signing task; no sign-in, the app closes when it is done.
            log.info("Started from a website launch link — opening the signing task");
            String code = launchArgument.value();
            launchArgument = null;
            navigator.openLaunch(stage, code);
        } else if (launchArgument != null) {
            // Invited signer: no SignFlow account, so no sign-in. They get the
            // invitation, a guest session for that one task, and the app
            // closes when they are done.
            log.info("Started from an invitation link — guest signing, no sign-in");
            String token = launchArgument.value();
            launchArgument = null;
            navigator.acceptInvitation(stage, token);
        } else if (navigator.signIn()) {
            navigator.runHome();
        }
        Platform.exit();
    }

    @Override
    public void stop() {
        log.info("Shutting down application");
        activations.close();
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
            context = app.run(springArguments(getParameters().getRaw()));
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
     * and, when that launch carried a link, open it.
     *
     * @return true when a window was raised (or the link will open)
     */
    private boolean raiseForActivation(InvitationLinks.LaunchArgument forwarded) {
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
        boolean raised = target != null && (target.isShowing() || forwarded != null);
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
        if (forwarded != null) {
            // Next pulse: the flow waits for the user, while the second launch
            // must get its answer now.
            Window owner = target != null ? target : stage;
            if (forwarded.kind() == InvitationLinks.LaunchArgument.Kind.LAUNCH) {
                log.info("Second launch forwarded a website launch link");
                Platform.runLater(() -> navigator.openLaunch(owner, forwarded.value()));
            } else {
                log.info("Second launch forwarded an invitation link");
                Platform.runLater(() -> navigator.acceptInvitation(owner, forwarded.value()));
            }
        }
        return raised;
    }

    /** What a second launch tells the user when it could not hand over (nothing was sent). */
    static String alreadyRunningMessage(SingleInstanceGuard.Outcome outcome,
                                        InvitationLinks.LaunchArgument link) {
        String state = outcome == SingleInstanceGuard.Outcome.OTHER_UNRESPONSIVE
                ? "SignFlow is already open but is busy and did not respond."
                : "SignFlow is already open, but this window could not safely pass"
                        + " anything to it.";
        if (link == null) {
            return state + " Switch to the open SignFlow window.";
        }
        String again = link.kind() == InvitationLinks.LaunchArgument.Kind.LAUNCH
                ? " click \"Sign\" again on the SignFlow website (each signing link"
                        + " works once, for 2 minutes)."
                : " click the invitation link in your e-mail again.";
        return state + " For your security, the link you clicked was not passed on."
                + " Finish or close the open SignFlow window, then" + again;
    }

    /**
     * Only Spring options ({@code --name=value}) reach Spring: a
     * {@code signflow://} link carries a one-time secret and must not end up
     * in Spring's argument beans or logs.
     */
    static String[] springArguments(List<String> raw) {
        return raw.stream()
                .filter(a -> a != null && a.startsWith("--"))
                .toArray(String[]::new);
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
