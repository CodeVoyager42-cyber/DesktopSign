/**
 * Root of the PDF digital-signer desktop application.
 *
 * <p>{@link SignerApplication} bootstraps Spring Boot in
 * {@code WebApplicationType.NONE} mode: the container exists purely for
 * dependency injection — there is no HTTP surface. The JavaFX layer
 * ({@code com.example.signer.ui}) starts the context from its
 * {@code Application.init()} and consumes the services below.</p>
 *
 * <p>Package layout:</p>
 * <ul>
 *   <li>{@code config} — typed {@code signature.*} configuration properties</li>
 *   <li>{@code exception} — error codes and the service exception type</li>
 *   <li>{@code pdf} — PDF parsing, analysis and rendering (PDFBox)</li>
 *   <li>{@code signing} — visible placement model, stamp rendering and the
 *       incremental CMS signing service (PDFBox + BouncyCastle)</li>
 *   <li>{@code token} — vendor-independent PKCS#11 token lifecycle
 *       ({@code mock} development token, {@code pkcs11} SunPKCS11 adapter)</li>
 *   <li>{@code ui} — JavaFX views, workspaces and shared view helpers</li>
 *   <li>{@code verification} — chain-aware, streaming signature verification</li>
 *   <li>{@code workflow} — sequential multi-signer state machine and engine</li>
 * </ul>
 *
 * <p>Cross-cutting rules: the private key never leaves the token; PINs live
 * in wiped {@code char[]} buffers and are never logged; expensive work
 * (parsing, hashing, crypto, rendering) never runs on the JavaFX
 * application thread.</p>
 */
package com.example.signer;
