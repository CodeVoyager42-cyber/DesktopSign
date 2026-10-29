package com.example.signer.ui.dialog;

import com.example.signer.client.RemoteApiException;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts;
import com.example.signer.ui.kit.SignFlowUi;
import com.example.signer.ui.support.Background;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

/**
 * Phase B: opening a signing task from a website launch link. Exchanges the
 * one-time code (once, in the background) for a task session and hands it
 * back; on failure, says why in plain words. The code is never shown or
 * logged.
 */
public final class LaunchDialog {

    /** The opened task session. */
    public record Result(WorkflowApiClient client, ApiContracts.LaunchSessionView session) {
    }

    private final String baseUrl;
    private final String code;
    private final Stage dialog = new Stage();
    private final StackPane root = new StackPane();
    private Result result;

    public LaunchDialog(String baseUrl, String code) {
        this.baseUrl = baseUrl;
        this.code = code;
    }

    /** Shows the dialog until the session is open or the user closes it. */
    public Result showAndWait(Window owner) {
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("SignFlow");
        root.setPadding(new Insets(28));
        root.setPrefWidth(520);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(46, 46);
        VBox loading = SignFlowUi.card(spinner,
                SignFlowUi.title("Opening your signing task…"),
                SignFlowUi.text("Checking the signing link with the SignFlow server."));
        loading.setAlignment(Pos.TOP_CENTER);
        root.getChildren().setAll(loading);
        dialog.setScene(new Scene(root));
        Task<ApiContracts.LaunchSessionView> exchange = new Task<>() {
            @Override
            protected ApiContracts.LaunchSessionView call() {
                return WorkflowApiClient.exchangeLaunchCode(baseUrl, code,
                        WorkflowApiClient.appVersion(), deviceLabel());
            }
        };
        exchange.setOnSucceeded(e -> {
            ApiContracts.LaunchSessionView session = exchange.getValue();
            result = new Result(WorkflowApiClient.forTask(baseUrl, session), session);
            dialog.close();
        });
        exchange.setOnFailed(e -> showProblem(exchange.getException()));
        Background.run("launch", exchange);
        dialog.showAndWait();
        return result;
    }

    private void showProblem(Throwable failure) {
        Button close = SignFlowUi.primary("Close", FontAwesomeSolid.TIMES);
        close.setOnAction(e -> dialog.close());
        String[] text = problemText(failure);
        root.getChildren().setAll(SignFlowUi.statePanel(FontAwesomeSolid.EXCLAMATION_TRIANGLE,
                SignFlowUi.Tone.WARN, text[0], text[1], close));
    }

    /** Title and explanation for a failed exchange (no code, no internals). */
    static String[] problemText(Throwable failure) {
        if (failure instanceof RemoteApiException api && api.serverCode() != null) {
            switch (api.serverCode()) {
                case "UPGRADE_REQUIRED":
                    return new String[] {"Please update SignFlow", api.getMessage()};
                case "LAUNCH_CODE_INVALID":
                    return new String[] {"This signing link can't be used",
                            "It may have expired (links work for 2 minutes), been used already,"
                                    + " or been cancelled. Go back to the SignFlow website and click"
                                    + " \"Sign\" again."};
                case "TOO_MANY_REQUESTS":
                    return new String[] {"Too many attempts",
                            "Wait a minute, then click \"Sign\" again on the website."};
                default:
                    break;
            }
        }
        if (failure instanceof SignerException se && WorkflowApiClient.isTransportFailure(se)) {
            return new String[] {"The SignFlow server can't be reached",
                    "Check your internet connection, then click \"Sign\" again on the website."};
        }
        return new String[] {"The signing task could not be opened",
                "Go back to the SignFlow website and click \"Sign\" again."};
    }

    /** A harmless label shown back to the user ("opened on …"). */
    private static String deviceLabel() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("HOSTNAME");
        }
        String os = System.getProperty("os.name", "");
        return (host == null || host.isBlank() ? "" : host + " ") + "(" + os + ")";
    }
}
