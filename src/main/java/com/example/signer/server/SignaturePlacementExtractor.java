package com.example.signer.server;

import com.example.signer.signing.SignaturePlacement;
import com.example.signer.workflow.WorkflowManifest;
import com.example.signer.workflow.WorkflowManifestCodec;
import com.example.signer.workflow.WorkflowManifestStore;
import com.example.signer.workflow.WorkflowSignerStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts the accepted visible-signature placement of the signer that just
 * signed (brief §33): the PDF's embedded workflow manifest already carries a
 * per-step {@code placement} in PDF points (origin bottom-left); this helper
 * reads it back out of the accepted revision and maps it onto the signer
 * slot, so placement becomes durable workflow metadata instead of living
 * only in JavaFX memory.
 *
 * <p>Coordinates (brief §33): zero-based page index, x/y in PDF points with
 * the origin at the page's bottom-left corner, width/height in points. The
 * manifest stores post-transform PDF-space values; no screen pixels.
 *
 * <p>Placement is metadata: any read problem degrades to {@code null} and
 * never rejects a cryptographically valid submission.
 */
@Component
public class SignaturePlacementExtractor {

    private static final Logger log = LoggerFactory.getLogger(
            SignaturePlacementExtractor.class);

    /**
     * The placement of the manifest step whose signature fulfilled the
     * newest revision, or {@code null} when the document carries no
     * (parseable) manifest or the step has no placement.
     *
     * @param workflowId     diagnostics only
     * @param acceptedPdf    the validated, accepted revision bytes
     * @param parentRevision the revision that was signed over; the accepted
     *                       signature is signature number
     *                       {@code parentRevision + 1} (1-based) / index
     *                       {@code parentRevision} (0-based)
     */
    public SignaturePlacement extract(String workflowId, byte[] acceptedPdf,
                                      int parentRevision) {
        Path temp = null;
        try {
            temp = Files.createTempFile("wf-placement-", ".pdf");
            Files.write(temp, acceptedPdf);
            WorkflowManifestCodec.ParseResult parsed =
                    WorkflowManifestStore.tryRead(temp);
            if (parsed == null || !parsed.ok()) {
                return null;
            }
            WorkflowManifest manifest = parsed.manifest();
            int newestSignatureIndex = parentRevision; // 0-based
            for (WorkflowSignerStep step : manifest.stepsInOrder()) {
                Integer revisionIndex = step.revisionIndex();
                if (revisionIndex != null
                        && revisionIndex == newestSignatureIndex
                        && step.placement() != null) {
                    return step.placement();
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Placement extraction skipped for {}: {}", workflowId,
                    e.toString());
            return null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }
}
