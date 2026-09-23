/**
 * Multi-signing workflow domain model.
 *
 * <p>{@link com.example.signer.workflow.SigningWorkflow} composes a
 * {@link com.example.signer.workflow.SigningMode} with an ordered list of
 * {@link com.example.signer.workflow.Signer}s whose progress is tracked via
 * {@link com.example.signer.workflow.SigningStatus}. The sequential signing
 * engine and the workflow-selection UI are both driven from this model, so
 * new modes (more signers, custom counts) need no engine changes.</p>
 */
package com.example.signer.workflow;
