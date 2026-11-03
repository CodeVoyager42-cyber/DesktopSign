package com.example.signer.server.api;

import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * The bits of workflow handling that both chains need and neither owns.
 *
 * <p>Phase E gave the website its own controllers (deliberately, see
 * {@code web/WebWorkflowController}), and a second copy of a rule is how two
 * chains quietly stop agreeing: one tightened, the other forgotten. So what
 * is shared lives here and the controllers keep only what is theirs —
 * resolving the caller from their own credential, and the HTTP status of the
 * answer.
 *
 * <p>This holds no authorization rules of its own: every workflow path starts
 * with {@code RemoteWorkflowService}, whose uniform 404 is the Phase 3 guard.
 */
@Component
public class WorkflowOperations {

    /**
     * The uploaded part has to be a PDF, and has to be there. Both chains
     * take the document as multipart, so both ask this.
     */
    public byte[] requirePdf(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The original PDF file is required");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "Only PDF documents can be signed");
        }
        return file.getBytes();
    }

    /**
     * A {@code Content-Disposition} value for a document download.
     *
     * <p>A document's name is whatever the uploader typed — it is validated
     * only for being non-blank — and both download routes put it in a header.
     * Quoted straight in, a name containing a {@code "} ends the filename
     * early and the rest is read as more header parameters; one containing a
     * newline is a header-splitting attempt that the container answers with a
     * 500 rather than a download. Neither is reachable through this method:
     * anything outside a conservative set becomes {@code _}, and a name left
     * with nothing usable becomes {@code document.pdf}.
     *
     * <p>Accents and non-Latin scripts are flattened too, which is a real
     * cost for anyone naming files in their own language. It is accepted for
     * now because the alternative — RFC 5987's {@code filename*} — deserves
     * its own test for how each browser reads it, rather than being slipped
     * in behind a security fix.
     */
    public static String attachment(String documentName) {
        String safe = documentName == null ? ""
                : documentName.trim().replaceAll("[^A-Za-z0-9 ._-]", "_");
        if (safe.length() > 100) {
            safe = safe.substring(0, 100);
        }
        // A name that was all punctuation leaves underscores and dots behind.
        if (safe.isBlank() || safe.chars().noneMatch(Character::isLetterOrDigit)) {
            safe = "document.pdf";
        }
        return "attachment; filename=\"" + safe + "\"";
    }
}
