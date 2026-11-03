/**
 * The coordination server's workflow core: the
 * {@link com.example.signer.server.workflow.SigningWorkflowAggregate} and its
 * records, the {@link com.example.signer.server.workflow.WorkflowState} machine,
 * {@link com.example.signer.server.workflow.RemoteWorkflowService} (turns,
 * certificate binding, server-side validation of every signed upload) and the
 * {@link com.example.signer.server.workflow.WorkflowStore} abstraction with its
 * file-based implementation. The JPA implementation lives in {@code server.db}.
 */
package com.example.signer.server.workflow;
