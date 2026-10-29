package com.example.signer.server.db;

import com.example.signer.server.workflow.SigningWorkflowAggregate;
import com.example.signer.server.workflow.WorkflowFileStore;
import com.example.signer.server.workflow.WorkflowStore;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bulk file→DB importer (brief Phase 1: "migrate persistent domain data").
 * On the first database-mode startup this copies every workflow from the
 * legacy file store into PostgreSQL, creating placeholder accounts for
 * usernames that are not yet in the users table (FKs require them). PDF
 * bytes are NOT copied — both stores share the same on-disk revision
 * layout, so the existing files keep serving both modes.
 *
 * <p>Idempotent: a workflow id already present in PostgreSQL is skipped;
 * the legacy state.json files are left untouched (read-only source). If the
 * storage root is empty, this logs and exits.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class FileStoreImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileStoreImporter.class);

    private final WorkflowStore target;
    /**
     * The legacy file store, constructed directly: in database mode the
     * {@link WorkflowFileStore} bean is intentionally absent (its condition
     * is the inverse of this importer's), but its reader logic is exactly
     * what the source side of the migration needs.
     */
    private final WorkflowFileStore legacy;
    private final UserRepository users;

    public FileStoreImporter(WorkflowStore target,
                             @Value("${signature.remote-server.storage:}") String storage,
                             DocumentFileStorage blobs,
                             UserRepository users) {
        this.target = target;
        this.legacy = new WorkflowFileStore(storage, blobs);
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!legacy.workflowsDir().toFile().exists()) {
            return;
        }
        int imported = 0;
        int skipped = 0;
        for (String workflowId : legacy.listWorkflowIds()) {
            if (target.exists(workflowId)) {
                skipped++;
                continue;
            }
            try {
                SigningWorkflowAggregate wf =
                        legacy.load(workflowId);
                if (wf == null) {
                    continue;
                }
                if (wf.createdBy() == null || wf.createdBy().isBlank()) {
                    // workflows.created_by is NOT NULL; a snapshot with no
                    // creator anywhere (no audit actor, no revision author)
                    // cannot be assigned an owner, so importing it would be
                    // guesswork. Report it instead of letting the INSERT fail.
                    log.warn("Import skipped for {}: the legacy snapshot records"
                            + " no creator (neither the WORKFLOW_CREATED audit"
                            + " event nor revision 0 names one)", workflowId);
                    continue;
                }
                ensureAccounts(wf);
                target.save(wf.asNewForImport());
                imported++;
            } catch (RuntimeException e) {
                log.warn("Import skipped for unreadable workflow {}: {}",
                        workflowId, e.toString());
            }
        }
        if (imported > 0 || skipped > 0) {
            log.info("File→DB import: {} workflow(s) imported, {} already"
                    + " present (skipped)", imported, skipped);
        }
    }

    /**
     * Placeholder accounts (PENDING_INVITE, no password) for workflow
     * usernames missing from the users table, so the signer/creator FKs
     * hold. Real accounts (dev-seeded or registered) are left alone.
     */
    private void ensureAccounts(SigningWorkflowAggregate wf) {
        ensureUser(wf.createdBy());
        wf.signers().forEach(s -> ensureUser(s.userId()));
    }

    private void ensureUser(String username) {
        if (username == null || username.isBlank() || "system".equals(username)) {
            return;
        }
        if (!users.existsByUsernameIgnoreCase(username)) {
            UserEntity placeholder = new UserEntity();
            placeholder.setUsername(username);
            placeholder.setDisplayName(capitalize(username));
            placeholder.setStatus(AccountStatus.PENDING_INVITE);
            users.save(placeholder);
        }
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
