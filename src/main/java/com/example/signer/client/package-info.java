/**
 * Desktop-side client of the SignFlow server, with no JavaFX dependency.
 *
 * <p>{@link com.example.signer.client.WorkflowApiClient} speaks the REST API
 * (JWT login/refresh, guest sessions, tasks, documents, uploads), exposed to
 * callers through the narrow {@link com.example.signer.client.SignerTasksApi}
 * and {@link com.example.signer.client.WorkflowOwnerApi} views.
 * {@link com.example.signer.client.RemoteSigningService} runs one remote
 * signature end to end (download, validate, sign with the card, submit
 * idempotently). The screens using it live in {@code ui.remote}.</p>
 */
package com.example.signer.client;
