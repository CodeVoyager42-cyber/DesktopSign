/**
 * JavaFX user interface of the application.
 *
 * <p>{@link SignerFxApplication} is the entry point:
 * login gate → workflow selection (1/2/3 signers) → either the single-signer
 * workspace or the per-signer multi-signer workspace, both under
 * {@link com.example.signer.ui.workspace}.</p>
 *
 * <p>Rules respected throughout this layer: no business logic or token
 * access outside the service layer; every expensive operation on a
 * background {@code Task}; failures mapped through
 * {@link ErrorMessages}; shared helpers live in
 * {@link com.example.signer.ui.support}.</p>
 */
package com.example.signer.ui;
