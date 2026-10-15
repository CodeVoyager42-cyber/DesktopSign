package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.AuditEventEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Append-only audit trail of a workflow. */
public interface AuditEventRepository
        extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByWorkflowIdOrderByEventSeq(Long workflowId);

    /** Highest sequence number of the workflow's trail (0 when empty). */
    @Query("select coalesce(max(e.eventSeq), 0) from AuditEventEntity e"
            + " where e.workflowId = :workflowId")
    long maxSequence(@Param("workflowId") Long workflowId);
}
