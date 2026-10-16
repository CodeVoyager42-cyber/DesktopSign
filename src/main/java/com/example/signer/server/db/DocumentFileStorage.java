package com.example.signer.server.db;

import com.example.signer.server.workflow.WorkflowIds;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Filesystem blob storage for document bytes (brief §25/§47): PostgreSQL
 * stores only metadata and hashes; the actual PDFs live under the same
 * directory layout the file-based store always used, so the two store
 * implementations are byte-compatible.
 *
 * <pre>
 * {base}/workflows/{workflowId}/revisions/0.pdf   — immutable revisions
 * {base}/workflows/{workflowId}/original.pdf      — staged DRAFT original
 * </pre>
 *
 * Revision files are written once and never modified; writing an
 * already-existing revision is refused (append-only). This component is the
 * single seam a future object-storage implementation would replace.
 */
@Component
public class DocumentFileStorage {

    private final Path base;

    public DocumentFileStorage(
            @Value("${signature.remote-server.storage:}") String storage) {
        String dir = storage == null || storage.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/signer-server"
                : storage;
        this.base = Path.of(dir);
    }

    public Path workflowsDir() {
        return base.resolve("workflows");
    }

    /**
     * Persists {@code bytes} as the immutable revision file {@code number}.
     * Writing an already-existing revision is refused (append-only store).
     */
    public void writeRevision(String workflowId, int number, byte[] bytes) {
        Path revisionsDir = base.resolve("workflows")
                .resolve(WorkflowIds.requireSafe(workflowId)).resolve("revisions");
        Path target = revisionsDir.resolve(number + ".pdf");
        if (Files.exists(target)) {
            throw new SignerException(ErrorCode.REVISION_CONFLICT,
                    "Revision " + number + " already exists for workflow " + workflowId);
        }
        try {
            Files.createDirectories(revisionsDir);
            Path temp = Files.createTempFile(revisionsDir, "rev-", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new SignerException(ErrorCode.PDF_SIGNING_FAILED,
                    "Revision " + number + " could not be stored", e);
        }
    }

    /**
     * Stages the uploaded original PDF for a DRAFT workflow. Revision 0 (the
     * manifest-bearing prepared document) is produced from these bytes once
     * the last signer has bound their certificate. This is NOT a revision —
     * the revisions/ directory stays append-only and untouched until then.
     */
    public void stageOriginal(String workflowId, byte[] bytes) {
        Path target = base.resolve("workflows").resolve(WorkflowIds.requireSafe(workflowId))
                .resolve("original.pdf");
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The original document could not be staged", e);
        }
    }

    /** The staged original of a DRAFT workflow, or {@code null} when absent. */
    public byte[] readStagedOriginal(String workflowId) {
        Path target = base.resolve("workflows").resolve(WorkflowIds.requireSafe(workflowId))
                .resolve("original.pdf");
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The staged original document could not be read", e);
        }
    }

    /** Whether the immutable revision {@code number} file exists. */
    public boolean existsRevision(String workflowId, int number) {
        return Files.exists(base.resolve("workflows")
                .resolve(WorkflowIds.requireSafe(workflowId)).resolve("revisions")
                .resolve(number + ".pdf"));
    }

    /** Reads the stored bytes of revision {@code number}. */
    public byte[] readRevision(String workflowId, int number) {
        Path target = base.resolve("workflows")
                .resolve(WorkflowIds.requireSafe(workflowId)).resolve("revisions").resolve(number + ".pdf");
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Revision " + number + " could not be read", e);
        }
    }

    /**
     * Deletes revision {@code number} — compensation only, for a revision
     * file whose state change could not be committed. Missing files are
     * ignored.
     */
    public void deleteRevision(String workflowId, int number) {
        Path target = base.resolve("workflows").resolve(WorkflowIds.requireSafe(workflowId))
                .resolve("revisions").resolve(number + ".pdf");
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Uncommitted revision " + number + " could not be removed", e);
        }
    }
}
