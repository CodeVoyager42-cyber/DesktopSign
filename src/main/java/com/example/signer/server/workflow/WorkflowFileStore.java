package com.example.signer.server.workflow;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.server.db.DocumentFileStorage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filesystem persistence for server-coordinated workflows — the
 * {@link WorkflowStore} implementation for the legacy/dev mode (active when
 * {@code signature.remote-server.database.enabled} is not {@code true}).
 *
 * <p>Layout:
 *
 * <pre>
 * {base}/workflows/{workflowId}/state.json          — aggregate snapshot
 * {base}/workflows/{workflowId}/revisions/0.pdf     — immutable revisions
 * {base}/workflows/{workflowId}/revisions/1.pdf
 * </pre>
 *
 * <p>{@code state.json} is rewritten atomically (temp file + move) after
 * every mutation; revision files are delegated to {@link DocumentFileStorage}
 * (identical layout to the PostgreSQL mode, so both stores are
 * byte-compatible). A per-workflow lock object serialises concurrent
 * submissions.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "false", matchIfMissing = true)
public class WorkflowFileStore implements WorkflowStore {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFileStore.class);

    private final Path base;
    private final DocumentFileStorage blobs;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** One lock object per workflow id, retained for the server's lifetime. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public WorkflowFileStore(
            @Value("${signature.remote-server.storage:}") String storage,
            DocumentFileStorage blobs) {
        String dir = storage == null || storage.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/signer-server"
                : storage;
        this.base = Path.of(dir);
        this.blobs = blobs;
        try {
            Files.createDirectories(base.resolve("workflows"));
        } catch (IOException e) {
            throw new UncheckedIOException("Server storage could not be created", e);
        }
    }

    public Path workflowsDir() {
        return base.resolve("workflows");
    }

    /** The per-workflow lock used to serialise submissions. */
    @Override
    public Object lockFor(String workflowId) {
        return locks.computeIfAbsent(workflowId, id -> new Object());
    }

    @Override
    public boolean exists(String workflowId) {
        if (!WorkflowIds.isSafe(workflowId)) {
            return false;
        }
        return Files.exists(base.resolve("workflows")
                .resolve(WorkflowIds.requireSafe(workflowId)).resolve("state.json"));
    }

    @Override
    public List<String> listWorkflowIds() {
        File[] dirs = workflowsDir().toFile().listFiles();
        if (dirs == null) {
            return List.of();
        }
        return Arrays.stream(dirs)
                .filter(File::isDirectory)
                .map(File::getName)
                .toList();
    }

    /** Loads the aggregate for {@code workflowId}, or {@code null} when absent. */
    @Override
    public SigningWorkflowAggregate load(String workflowId) {
        if (!WorkflowIds.isSafe(workflowId)) {
            return null; // never a valid id: behaves like an unknown workflow
        }
        Path stateFile = base.resolve("workflows")
                .resolve(WorkflowIds.requireSafe(workflowId)).resolve("state.json");
        if (!Files.exists(stateFile)) {
            return null;
        }
        try {
            SnapshotDto dto = mapper.readValue(stateFile.toFile(), SnapshotDto.class);
            return SigningWorkflowAggregate.rehydrateStored(workflowId, dto.documentName(),
                    dto.policy(), dto.signers(), dto.revisions(), dto.audit(),
                    dto.status(), dto.blockedReason(),
                    dto.claimedTurnUserId(), dto.claimedTurnAt(), creatorOf(dto),
                    dto.version() == null ? 0L : dto.version());
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The workflow state could not be read", e);
        }
    }

    /**
     * The creator recorded in a snapshot. Snapshots written before the
     * creator was stored at workflow level carry no {@code createdBy}, but
     * the information IS in the snapshot: the identity that stored revision
     * 0 (the original document) created the workflow, and the
     * {@code WORKFLOW_CREATED} audit event names the same actor. Recovering
     * it here keeps the creator non-null for consumers with stricter
     * requirements than the file store itself (the DB importer inserts into
     * a {@code created_by NOT NULL} column) without inventing a value.
     *
     * @return the creator, or {@code null} when the snapshot truly has none.
     */
    static String creatorOf(SnapshotDto dto) {
        if (dto.createdBy() != null && !dto.createdBy().isBlank()) {
            return dto.createdBy();
        }
        if (dto.revisions() != null) {
            for (DocumentRevisionRecord revision : dto.revisions()) {
                if (revision.revisionNumber() == 0
                        && revision.createdBy() != null
                        && !revision.createdBy().isBlank()) {
                    return revision.createdBy();
                }
            }
        }
        if (dto.audit() != null) {
            for (AuditEventRecord event : dto.audit()) {
                if (event.eventType() == AuditEventType.WORKFLOW_CREATED
                        && event.actor() != null
                        && !event.actor().isBlank()) {
                    return event.actor();
                }
            }
        }
        return null;
    }

    /**
     * Persists the aggregate snapshot atomically, with optimistic version
     * checking: the snapshot on disk must still be at the version the
     * aggregate was loaded from (or absent for a NEW aggregate). The check
     * and the write run under the workflow's lock, so they cannot interleave
     * with another save of the same workflow in this process.
     */
    @Override
    public void save(SigningWorkflowAggregate aggregate) {
        synchronized (lockFor(aggregate.workflowId())) {
            SigningWorkflowAggregate.Snapshot snap = aggregate.snapshot();
            Path dir = base.resolve("workflows").resolve(WorkflowIds.requireSafe(aggregate.workflowId()));
            Path stateFile = dir.resolve("state.json");
            SigningWorkflowAggregate stored = Files.exists(stateFile)
                    ? load(aggregate.workflowId()) : null;
            if (stored == null && !aggregate.isNew()) {
                throw new WorkflowConflictException("Workflow "
                        + aggregate.workflowId() + " no longer exists");
            }
            if (stored != null) {
                if (aggregate.isNew()) {
                    throw new WorkflowConflictException("Workflow "
                            + aggregate.workflowId() + " already exists");
                }
                if (stored.version() != aggregate.version()) {
                    throw new WorkflowConflictException("Workflow "
                            + aggregate.workflowId() + " was changed by another"
                            + " request (stored version " + stored.version()
                            + ", loaded version " + aggregate.version() + ")");
                }
                WorkflowRegressionGuard.check(stored, aggregate);
            }
            long nextVersion = stored == null ? 0L : stored.version() + 1;
            SnapshotDto dto = new SnapshotDto(snap.documentName(), snap.policy(),
                    snap.status(), snap.blockedReason(), snap.claimedTurnUserId(),
                    snap.claimedTurnAt(), snap.createdBy(), snap.signers(),
                    snap.revisions(), renumberAudit(stored, aggregate), nextVersion);
            try {
                Files.createDirectories(dir);
                Path temp = Files.createTempFile(dir, "state-", ".tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), dto);
                try {
                    Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                        "The workflow state could not be persisted", e);
            }
            aggregate.markPersisted(nextVersion);
        }
    }

    /**
     * The audit list to persist: the stored events unchanged, followed by
     * the aggregate's unsaved events renumbered after the stored maximum, so
     * sequence numbers stay unique and contiguous whatever the aggregate
     * assigned in memory.
     */
    private static List<AuditEventRecord> renumberAudit(SigningWorkflowAggregate stored,
                                                        SigningWorkflowAggregate aggregate) {
        List<AuditEventRecord> persisted = stored == null ? List.of() : stored.audit();
        long next = persisted.stream().mapToLong(AuditEventRecord::sequence).max().orElse(0L) + 1;
        List<AuditEventRecord> result = new ArrayList<>(persisted);
        for (AuditEventRecord e : aggregate.unsavedAuditEvents()) {
            result.add(new AuditEventRecord(next++, e.eventType(), e.actor(),
                    e.detail(), e.occurredAt()));
        }
        return result;
    }

    @Override
    public void discardUncommittedRevision(String workflowId, int number) {
        blobs.deleteRevision(workflowId, number);
    }

    @Override
    public void writeRevision(String workflowId, int number, byte[] bytes) {
        blobs.writeRevision(workflowId, number, bytes);
    }

    @Override
    public void stageOriginal(String workflowId, byte[] bytes) {
        blobs.stageOriginal(workflowId, bytes);
    }

    @Override
    public byte[] readStagedOriginal(String workflowId) {
        return blobs.readStagedOriginal(workflowId);
    }

    @Override
    public boolean existsRevision(String workflowId, int number) {
        return blobs.existsRevision(workflowId, number);
    }

    @Override
    public byte[] readRevision(String workflowId, int number) {
        return blobs.readRevision(workflowId, number);
    }

    /** Lower-case hex SHA-256 of {@code bytes}. */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Lower-case hex SHA-256 of the file at {@code path}. */
    static String sha256HexOfFile(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
