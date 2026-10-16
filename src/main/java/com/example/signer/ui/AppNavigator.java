package com.example.signer.ui;

import com.example.signer.ui.dialog.InvitationDialog;
import com.example.signer.ui.dialog.LoginDialog;
import com.example.signer.ui.dialog.WorkflowSelectionDialog;
import com.example.signer.ui.remote.RemoteWorkflowDialog;
import com.example.signer.ui.workspace.MultiSignerWorkspaceFlow;
import com.example.signer.ui.workspace.WorkflowContinuationDialog;
import com.example.signer.workflow.SigningMode;

import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;

/**
 * Where the user goes: sign-in, the home screen ("what do you want to do?")
 * and every flow it opens. Each flow runs until its window closes, then the
 * user is back on the home screen — so they can start another one without
 * restarting the app.
 *
 * <p>This class only decides which screen comes next; the screens
 * themselves do the work, with the services handed in here.
 */
final class AppNavigator {

    private static final Logger log = LoggerFactory.getLogger(AppNavigator.class);

    private final DesktopServices services;
    /** Hidden owner window of every flow (keeps them on the same taskbar entry). */
    private final Stage owner;

    AppNavigator(DesktopServices services, Stage owner) {
        this.services = services;
        this.owner = owner;
    }

    /** The sign-in screen. @return true when the user signed in */
    boolean signIn() {
        boolean ok = new LoginDialog(services.login()).showAndWait();
        log.info(ok ? "User signed in" : "Sign-in cancelled");
        return ok;
    }

    /**
     * The home screen loop. Returns when the user leaves the app (Back on
     * the home screen, closing it, or cancelling sign-in after signing out).
     */
    void runHome() {
        while (true) {
            WorkflowSelectionDialog home = new WorkflowSelectionDialog();
            SigningMode mode = home.showAndWait(owner);

            if (home.isSignOutRequested()) {
                if (!signIn()) {
                    return;
                }
            } else if (home.isOpenWorkflowRequested()) {
                openWorkflowContinuation();
            } else if (home.isRemoteWorkflowRequested()) {
                remoteWorkflowDialog()
                        .startWithNewWorkflow(home.isSendForSignatureRequested())
                        .showAndWait(owner);
            } else if (home.isAcceptInvitationRequested()) {
                acceptInvitation(owner, null);
            } else if (mode == null) {
                log.info("Home screen closed — exiting");
                return;
            } else {
                openSigningWorkspace(mode);
            }
        }
    }

    /**
     * Invitation flow: the invitation screen (pre-filled with {@code token}
     * when the app was opened from a link), then straight into that one
     * signing task as a guest — no account, no task lists.
     */
    void acceptInvitation(Window parent, String token) {
        InvitationDialog invitation = new InvitationDialog(services.remoteBaseUrl(), token);
        invitation.showAndWait(parent);
        InvitationDialog.Result result = invitation.getResult();
        if (result == null) {
            return;
        }
        log.info("Guest signing session opened for workflow {}", result.workflowId());
        try {
            remoteWorkflowDialog().showGuestAndWait(owner, result.client(), result.signerId(),
                    result.workflowId(), result.email(), result.documentName());
        } finally {
            // Wipes the guest token from memory (nothing to revoke server-side).
            result.client().close();
        }
    }

    /**
     * One, two or three people sign a PDF on this computer, one after the
     * other. One signer uses the very same workspace, so every local signing
     * looks and works the same way.
     */
    private void openSigningWorkspace(SigningMode mode) {
        Path pdf = choosePdf(mode.signerCount() == 1 ? "Choose the PDF to sign"
                : "Choose the PDF for the " + mode.signerCount() + "-signer workflow");
        if (pdf == null) {
            return;
        }
        new MultiSignerWorkspaceFlow(mode, pdf, services.workflowEngine(), services.tokens(),
                services.pdfRender(), services.pdfDocuments())
                .showAndWait(owner);
    }

    /** Continue a portable workflow PDF (it carries its own signing order). */
    private void openWorkflowContinuation() {
        Path pdf = choosePdf("Open a signing workflow document");
        if (pdf == null) {
            return;
        }
        new WorkflowContinuationDialog(services.workflowSigning(), services.tokens(),
                services.pdfRender())
                .showAndWait(owner, pdf);
    }

    private RemoteWorkflowDialog remoteWorkflowDialog() {
        return new RemoteWorkflowDialog(services.login(), services.remoteBaseUrl(),
                services.tokens(), services.workflowSigning(), services.pdfRender(),
                services.uploadLimit());
    }

    /** @return the chosen PDF, or null when the user cancelled */
    private Path choosePdf(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            log.info("Cancelled at document selection: {}", title);
            return null;
        }
        return file.toPath();
    }
}
