/**
 * The signing workspaces — the document-centric views where signers review,
 * place and create signatures.
 *
 * <p>{@link MultiSignerWorkspaceFlow} is the local signing workspace for
 * one, two or three signers on this computer (one turn at a time);
 * {@link WorkflowContinuationDialog} continues a workflow PDF in the same
 * layout, and both show documents through {@link PlacementViewer}.
 * {@link CoordinateMapper} is the single
 * screen→PDF transformation layer and
 * {@link ThumbnailPane} the lazy page
 * navigator. All overlays live in PDF-point space via the mapper, so
 * placement is zoom- and window-size invariant.</p>
 */
package com.example.signer.ui.workspace;
