package com.example.signer.ui;

import com.example.signer.config.LoginProperties;
import com.example.signer.pdf.PdfDocumentService;
import com.example.signer.pdf.PdfRenderService;
import com.example.signer.token.TokenService;
import com.example.signer.workflow.WorkflowSigningEngine;
import com.example.signer.workflow.WorkflowSigningService;

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

    /** Default upload limit when none is configured: 100 MB. */
    private static final long DEFAULT_UPLOAD_LIMIT = 104_857_600L;

    static DesktopServices from(ApplicationContext context) {
        Long configured = context.getEnvironment()
                .getProperty("signature.remote.upload.max-bytes", Long.class);
        return new DesktopServices(
                context.getBean(TokenService.class),
                context.getBean(PdfRenderService.class),
                context.getBean(PdfDocumentService.class),
                context.getBean(WorkflowSigningService.class),
                context.getBean(WorkflowSigningEngine.class),
                context.getBean(LoginProperties.class),
                context.getEnvironment().getProperty("signature.remote.base-url"),
                configured == null || configured <= 0 ? DEFAULT_UPLOAD_LIMIT : configured);
    }
}
