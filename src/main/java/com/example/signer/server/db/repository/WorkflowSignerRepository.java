package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.WorkflowSignerEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Signer slots of a workflow, in signing order. */
public interface WorkflowSignerRepository
        extends JpaRepository<WorkflowSignerEntity, Long> {

    List<WorkflowSignerEntity> findByWorkflowIdOrderBySignerOrder(Long workflowId);
}
