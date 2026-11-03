package com.example.signer.ui;

import com.example.signer.ui.dialog.LoginDialog;
import com.example.signer.ui.dialog.WorkflowSelectionDialog;
import com.example.signer.ui.remote.RemoteWorkflowDialog;
import com.example.signer.ui.workspace.SigningWorkspaceFlow;
import com.example.signer.ui.workspace.WorkflowContinuationDialog;

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
     * The home screen loop. Returns when the user leaves the app (Quit on
     * the home screen, closing it, or cancelling sign-in after signing out).
     */
    void runHome() {
        while (true) {
            WorkflowSelectionDialog home = new WorkflowSelectionDialog();
            home.showAndWait(owner);

            if (home.isSignOutRequested()) {
                if (!signIn()) {
                    return;
                }
            } else if (home.isOpenWorkflowRequested()) {
                openWorkflowContinuation();
            } else if (home.isRemoteWorkflowRequested()) {
                remoteWorkflowDialog().showAndWait(owner);
            } else if (home.isSignHereRequested()) {
                openSigningWorkspace();
            } else {
                log.info("Home screen closed — exiting");
                return;
            }
        }
    }

    /**
     * Phase B: opened from a website launch link. The one-time code is
     * exchanged for a session limited to that one signing task, then
     * straight to the signing screens — no sign-in, no document list. Local
     * PKCS#11 signing is unchanged.
     */
    void openLaunch(Window parent, String code) {
        com.example.signer.ui.dialog.LaunchDialog.Result result =
                new com.example.signer.ui.dialog.LaunchDialog(services.remoteBaseUrl(), code)
                        .showAndWait(parent);
        if (result == null) {
            return;
        }
        var session = result.session();
        log.info("Desktop task session opened for workflow {}", session.workflowId());
        try {
            remoteWorkflowDialog().showTaskSessionAndWait(owner, result.client(),
                    session.signerId(), session.workflowId(), session.documentName());
        } finally {
            // Wipes the task token from memory.
            result.client().close();
        }
    }

    /** The signer signs a PDF on this computer with their own USB token. */
    private void openSigningWorkspace() {
        Path pdf = choosePdf("Choose the PDF to sign");
        if (pdf == null) {
            return;
        }
        new SigningWorkspaceFlow(pdf, services.workflowEngine(), services.tokens(),
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
