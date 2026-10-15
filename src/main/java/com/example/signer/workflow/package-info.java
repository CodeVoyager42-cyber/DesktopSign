/**
 * Multi-signing workflow domain model.
 *
 * <p>{@link SigningWorkflow} composes a
 * {@link SigningMode} with an ordered list of
 * {@link Signer}s whose progress is tracked via
 * {@link SigningStatus}. The sequential signing
 * engine and the workflow-selection UI are both driven from this model, so
 * new modes (more signers, custom counts) need no engine changes.</p>
 */
package com.example.signer.workflow;
