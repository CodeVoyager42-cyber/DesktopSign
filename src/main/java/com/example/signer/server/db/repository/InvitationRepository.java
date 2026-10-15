package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.InvitationEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Email invitations. Only token HASHES are stored here — the raw token is
 * delivered to the signer exactly once and never persisted.
 */
public interface InvitationRepository
        extends JpaRepository<InvitationEntity, Long> {

    Optional<InvitationEntity> findByTokenHash(String tokenHash);

    List<InvitationEntity> findByWorkflowId(Long workflowId);

    /** The invitations of ONE signer slot (uses idx_invitations_workflow_slot). */
    List<InvitationEntity> findByWorkflowIdAndSignerOrder(Long workflowId, int signerOrder);

    /**
     * The owning workflow's database id, WITHOUT loading the invitation
     * entity: invitation mutations lock the workflow row first and only then
     * read the invitation, so they always see the committed state.
     */
    @Query("select i.workflowId from InvitationEntity i where i.tokenHash = :tokenHash")
    Optional<Long> findWorkflowIdByTokenHash(@Param("tokenHash") String tokenHash);

    /** As {@link #findWorkflowIdByTokenHash}, by invitation id. */
    @Query("select i.workflowId from InvitationEntity i where i.id = :id")
    Optional<Long> findWorkflowIdById(@Param("id") Long id);

    List<InvitationEntity> findByStatusAndExpiresAtBefore(String status, Instant now);

    /**
     * Immediately revokes every PENDING invitation of a signer slot. A bulk
     * update (not entity dirty-checking) so the revoke is flushed BEFORE a
     * replacement row is inserted — otherwise the one-pending-per-slot
     * partial index would see both rows during Hibernate's flush ordering
     * (inserts before updates).
     */
    @Modifying
    @Query("update InvitationEntity i set i.status = 'REVOKED'"
            + " where i.workflowId = :workflowId and i.signerOrder = :order"
            + " and i.status = 'PENDING'")
    int revokePending(@Param("workflowId") Long workflowId,
                      @Param("order") int signerOrder);
}
