/**
 * The signing workspaces — the document-centric views where signers review,
 * place and create signatures.
 *
 * <p>{@link com.example.signer.ui.workspace.SigningWorkspace} is the
 * single-signer workspace; {@link com.example.signer.ui.workspace.MultiSignerWorkspaceFlow}
 * drives the sequential multi-signer workflow one signer at a time.
 * {@link com.example.signer.ui.workspace.CoordinateMapper} is the single
 * screen→PDF transformation layer and
 * {@link com.example.signer.ui.workspace.ThumbnailPane} the lazy page
 * navigator. All overlays live in PDF-point space via the mapper, so
 * placement is zoom- and window-size invariant.</p>
 */
package com.example.signer.ui.workspace;
