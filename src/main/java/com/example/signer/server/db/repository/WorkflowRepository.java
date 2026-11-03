package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.WorkflowEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Workflow scalar state. */
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, Long> {

    Optional<WorkflowEntity> findByWorkflowId(String workflowId);

    boolean existsByWorkflowId(String workflowId);

    @Query("select w.workflowId from WorkflowEntity w order by w.workflowId")
    List<String> findAllWorkflowIds();

    /** Workflows in which {@code username} holds a signer slot (task lists). */
    @Query("select w.workflowId from WorkflowEntity w where w.id in"
            + " (select s.workflowId from WorkflowSignerEntity s where s.username = :username)"
            + " order by w.workflowId")
    List<String> findWorkflowIdsBySigner(@Param("username") String username);

    /** Workflows created by {@code username} (the owner's views). */
    @Query("select w.workflowId from WorkflowEntity w where w.createdBy = :username"
            + " order by w.workflowId")
    List<String> findWorkflowIdsByCreator(@Param("username") String username);

    /**
     * Loads the workflow row with {@code SELECT ... FOR UPDATE}: every
     * writer of a workflow's state or audit trail takes this row lock in its
     * transaction, so writers serialise in the DATABASE — across threads and
     * across server processes — and the lock is held until commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowEntity w where w.workflowId = :workflowId")
    Optional<WorkflowEntity> lockByWorkflowId(@Param("workflowId") String workflowId);

    /** As {@link #lockByWorkflowId}, by primary key. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowEntity w where w.id = :id")
    Optional<WorkflowEntity> lockById(@Param("id") Long id);
}
