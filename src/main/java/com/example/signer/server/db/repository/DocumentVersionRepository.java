package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.DocumentVersionEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Immutable document revisions of a workflow. */
public interface DocumentVersionRepository
        extends JpaRepository<DocumentVersionEntity, Long> {

    List<DocumentVersionEntity> findByWorkflowIdOrderByRevisionNumber(Long workflowId);
}
