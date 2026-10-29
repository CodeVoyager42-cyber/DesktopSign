/**
 * JavaFX user interface of the desktop application.
 *
 * <p>{@link com.example.signer.ui.SignerFxApplication} starts the app;
 * {@link com.example.signer.ui.AppNavigator} decides which screen comes next:
 * login gate, then the home screen, then one of the flows below. Each flow
 * returns to the home screen when its window closes.</p>
 * <ul>
 *   <li>{@code dialog} — login, home screen, invitation and inviter dialogs</li>
 *   <li>{@code workspace} — local signing (1–3 signers on this PC, continuing a
 *       workflow PDF) with the shared {@code PlacementViewer}</li>
 *   <li>{@code remote} — server-backed screens: signing tasks, the remote
 *       signing journey, the new-workflow wizard</li>
 *   <li>{@code kit} — reusable controls; {@code support} — threading, error
 *       messages and small helpers</li>
 * </ul>
 *
 * <p>Rules: no business logic or token access outside the service layer;
 * every slow operation on a background task; failures shown through
 * {@link com.example.signer.ui.support.ErrorMessages}.</p>
 */
package com.example.signer.ui;
