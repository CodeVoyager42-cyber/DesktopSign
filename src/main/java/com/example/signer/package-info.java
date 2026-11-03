/**
 * Root of the PDF digital-signer desktop application.
 *
 * <p>{@link SignerApplication} bootstraps Spring Boot in
 * {@code WebApplicationType.NONE} mode: the container exists purely for
 * dependency injection — there is no HTTP surface. The JavaFX layer
 * ({@code com.example.signer.ui}) starts the context from its
 * {@code Application.init()} and consumes the services below.</p>
 *
 * <p>Package layout (dependencies point down this list, never up):</p>
 * <ul>
 *   <li>{@code ui} — JavaFX desktop: app shell ({@code SignerFxApplication},
 *       {@code AppNavigator}), {@code dialog}, {@code workspace} (local signing),
 *       {@code remote} (server-backed screens), {@code kit} and {@code support}</li>
 *   <li>{@code client} — HTTP client for the SignFlow server and the remote
 *       signing service (no JavaFX)</li>
 *   <li>{@code server} — Spring Boot coordination server (profile {@code server}):
 *       {@code api}, {@code auth}, {@code invites}, {@code workflow} (domain,
 *       service, stores) and {@code db} (JPA persistence)</li>
 *   <li>{@code workflow} — portable workflow manifest, reconciliation and the
 *       local multi-signer engine</li>
 *   <li>{@code signing} — visible placement model, stamp rendering and the
 *       incremental CMS signing service (PDFBox + BouncyCastle)</li>
 *   <li>{@code verification} — chain-aware, streaming signature verification</li>
 *   <li>{@code pdf} — PDF parsing, analysis and rendering (PDFBox)</li>
 *   <li>{@code token} — vendor-independent PKCS#11 token lifecycle
 *       ({@code mock} development token, {@code pkcs11} SunPKCS11 adapter)</li>
 *   <li>{@code config}, {@code exception} — settings and error codes, used by all</li>
 * </ul>
 *
 * <p>Cross-cutting rules: the private key never leaves the token; PINs live
 * in wiped {@code char[]} buffers and are never logged; expensive work
 * (parsing, hashing, crypto, rendering) never runs on the JavaFX
 * application thread.</p>
 */
package com.example.signer;
