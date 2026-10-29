package com.example.signer.ui;

import com.example.signer.config.LoginProperties;
import com.example.signer.pdf.PdfDocumentService;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.token.TokenService;
import com.example.signer.workflow.WorkflowSigningEngine;
import com.example.signer.workflow.WorkflowSigningService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * Everything the desktop screens need from Spring, resolved ONCE at start-up.
 *
 * <p>Screens receive what they use through their constructors instead of
 * reaching into the Spring context themselves: the dependencies of each
 * screen are visible in one place, and the UI code does not depend on Spring.
 *
 * @param remoteBaseUrl  coordination server address ({@code signature.remote.base-url})
 * @param uploadLimit    largest document the server accepts, in bytes
 */
record DesktopServices(
        TokenService tokens,
        PdfRenderService pdfRender,
        PdfDocumentService pdfDocuments,
        WorkflowSigningService workflowSigning,
        WorkflowSigningEngine workflowEngine,
        LoginProperties login,
        String remoteBaseUrl,
        long uploadLimit) {

    private static final Logger log = LoggerFactory.getLogger(DesktopServices.class);

    /** Default upload limit when none is configured: 100 MB. */
    private static final long DEFAULT_UPLOAD_LIMIT = 104_857_600L;

    /** Shown when no address is configured at all - the UI needs something to say. */
    static final String NO_SERVER = "not configured";

    static DesktopServices from(ApplicationContext context) {
        Long configured = context.getEnvironment()
                .getProperty("signature.remote.upload.max-bytes", Long.class);
        String remote = context.getEnvironment().getProperty("signature.remote.base-url");
        // The address this copy will talk to, once, at start-up. The ADDRESS
        // only: no token, no credential, no document name. Which server a
        // desktop reached is the first thing worth knowing from a support
        // log, and an installer built for the wrong one is otherwise
        // invisible until a signing attempt fails.
        log.info("Coordination server: {}", displayAddress(remote));
        return new DesktopServices(
                context.getBean(TokenService.class),
                context.getBean(PdfRenderService.class),
                context.getBean(PdfDocumentService.class),
                context.getBean(WorkflowSigningService.class),
                context.getBean(WorkflowSigningEngine.class),
                context.getBean(LoginProperties.class),
                remote,
                configured == null || configured <= 0 ? DEFAULT_UPLOAD_LIMIT : configured);
    }

    /** The configured address, or {@link #NO_SERVER} - never null, never blank. */
    static String displayAddress(String url) {
        return url == null || url.isBlank() ? NO_SERVER : url.trim();
    }

    /** The address to show the user and log: see {@link #displayAddress}. */
    String serverAddress() {
        return displayAddress(remoteBaseUrl);
    }
}
