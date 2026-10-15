package com.example.signer.workflow;

import com.example.signer.verification.SignatureVerificationResult;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconciles the portable manifest against the PDF's actual signature chain
 * and computes the {@link EffectiveWorkflowState} the application acts on.
 *
 * <p><b>Trust model.</b> The signature chain (from
 * {@code PdfVerificationService}) is authoritative for what has been signed;
 * the manifest is metadata describing what should happen. Reconciliation maps
 * each valid signature — identified by its signer certificate's SHA-256
 * fingerprint — onto the manifest step carrying the same expected
 * fingerprint. A manifest step is "fulfilled" only when such a valid
 * signature exists; manifest text claiming otherwise is ignored.
 *
 * <p>Inconsistencies (a signature by an unexpected certificate, duplicate
 * signatures by one certificate, manifest claiming more signatures than the
 * chain shows, steps signed out of order) never block reading/verification —
 * they produce {@code warnings} and, for the dangerous ones, status
 * {@code MANIFEST_INCONSISTENT} with signing blockers.
 */
@Service
public class WorkflowReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowReconciliationService.class);

    /**
     * @param manifest  parsed manifest (must be schema-supported)
     * @param verification result of verifying the whole PDF signature chain
     */
    public EffectiveWorkflowState reconcile(WorkflowManifest manifest,
                                            SignatureVerificationResult verification) {
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        List<SignatureVerificationResult.SignatureDetail>
                signatures = verification.signatures();

        // ── cryptographic truth: the chain must be intact at all ──
        // A workflow PDF awaiting its FIRST signature is legitimately unsigned
        // (valid=false with zero signatures); that is NOT_STARTED, not tampered.
        boolean unsignedDocument = signatures.isEmpty();
        if (!verification.valid() && !unsignedDocument) {
            blockers.add("The document's signature chain is invalid: "
                    + verification.message());
            return new EffectiveWorkflowState(
                    EffectiveWorkflowState.WorkflowStatus.SIGNATURE_INVALID,
                    manifest, views(manifest, verification, warnings),
                    null, warnings, blockers);
        }

        // ── map signatures onto steps by certificate fingerprint ──
        // steps[i] corresponds to order i+1 by manifest validation.
        boolean[] fulfilled = new boolean[manifest.stepsInOrder().size()];
        Instant[] fulfilledAt = new Instant[fulfilled.length];
        int[] revisionOf = new int[fulfilled.length];
        Arrays.fill(revisionOf, -1);

        Set<String> seenFingerprints = new HashSet<>();
        boolean unexpectedSigner = false;
        boolean duplicateSigner = false;
        boolean outOfOrder = false;
        int highestFulfilledOrder = 0;
        // FLEXIBLE workflows legitimately produce signatures in any order;
        // only SEQUENTIAL makes out-of-order evidence inconsistent.
        boolean orderEnforced = manifest.policy() != WorkflowSigningPolicy.FLEXIBLE;

        for (int r = 0; r < signatures.size(); r++) {
            var sig = signatures.get(r);
            String fp = sig.signerCertFingerprint();
            if (fp == null) {
                warnings.add("Signature " + (r + 1)
                        + " carries no usable certificate fingerprint; it cannot be attributed to a workflow step.");
                continue;
            }
            int stepIdx = indexOfFingerprint(manifest, fp);
            if (stepIdx < 0) {
                unexpectedSigner = true;
                warnings.add("Signature " + (r + 1) + " was created by a certificate"
                        + " that is not assigned to any workflow step ("
                        + CertificateIdentityBrief.of(fp) + ").");
                continue;
            }
            if (!seenFingerprints.add(fp)) {
                duplicateSigner = true;
                warnings.add("Certificate " + CertificateIdentityBrief.of(fp)
                        + " appears in more than one signature.");
                continue;
            }
            int order = manifest.stepsInOrder().get(stepIdx).order();
            if (orderEnforced && order > highestFulfilledOrder + 1) {
                outOfOrder = true;
            }
            highestFulfilledOrder = Math.max(highestFulfilledOrder, order);
            fulfilled[stepIdx] = true;
            fulfilledAt[stepIdx] = sig.signedAt();
            revisionOf[stepIdx] = r;
        }

        // ── manifest claims vs chain reality ──
        // Manifest-persisted signedAt/revisionIndex that the chain does not
        // support are repaired here (manifest is metadata), but a manifest
        // that marks LATER steps done while the chain shows fewer valid
        // signatures is inconsistent, not merely stale.
        long validCount = signatures.size();
        long claimedDone = manifest.stepsInOrder().stream()
                .filter(s -> s.revisionIndex() != null)
                .count();
        boolean manifestOverclaims = claimedDone > validCount;
        if (manifestOverclaims) {
            warnings.add("The workflow information claims more completed signatures"
                    + " (" + claimedDone + ") than the document's valid signature chain"
                    + " shows (" + validCount + ").");
        }        EffectiveWorkflowState.WorkflowStatus status;
        boolean allDone = true;
        for (boolean f : fulfilled) {
            allDone = allDone && f;
        }
        if (allDone && !unexpectedSigner && !duplicateSigner && !outOfOrder
                && !manifestOverclaims) {
            status = EffectiveWorkflowState.WorkflowStatus.COMPLETED;
        } else if (unexpectedSigner || duplicateSigner || outOfOrder
                || manifestOverclaims) {
            status = EffectiveWorkflowState.WorkflowStatus.MANIFEST_INCONSISTENT;
            blockers.add("The workflow information does not match the signatures"
                    + " contained in this document. Signing has been disabled until"
                    + " the document can be verified by its participants.");
        } else if (signatures.isEmpty()) {
            // Intact, zero-signature document awaiting its first signer.
            status = EffectiveWorkflowState.WorkflowStatus.NOT_STARTED;
        } else {
            status = EffectiveWorkflowState.WorkflowStatus.IN_PROGRESS;
        }

        // ── certification permissions (DocMDP) ──
        // A document certified by an external tool with restrictive DocMDP
        // permissions (anything stricter than "form fill / signatures
        // allowed", P≠2) must not receive further workflow signatures:
        // they would violate the certification policy. Fail closed.
        Integer docMdp = signatures.stream()
                .map(SignatureVerificationResult
                        .SignatureDetail::docMdp)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        boolean certificationBlocksSigning = docMdp != null && docMdp != 2
                && status != EffectiveWorkflowState.WorkflowStatus.COMPLETED;
        if (certificationBlocksSigning) {
            warnings.add("The document carries a certification signature that "
                    + "restricts further changes (DocMDP P=" + docMdp + ").");
            blockers.add("The document's certification signature does not "
                    + "allow additional signatures.");
        }

        // ── whose turn ──
        // SEQUENTIAL: the first unfulfilled step in manifest order.
        // FLEXIBLE: no server-independent "current" step exists — any pending
        // signer may take the turn; the local domain resolves the step from
        // the CONNECTED token's certificate (see WorkflowSigningService).
        Integer current = null;
        if (manifest.policy() != WorkflowSigningPolicy.FLEXIBLE) {
            for (int i = 0; i < fulfilled.length; i++) {
                if (!fulfilled[i]) {
                    current = manifest.stepsInOrder().get(i).order();
                    break;
                }
            }
        }

        // Blocked when already done (or nothing left to do).
        if (status == EffectiveWorkflowState.WorkflowStatus.COMPLETED) {
            blockers.add("All required signatures have been completed.");
        }

        return new EffectiveWorkflowState(status, manifest,
                views(manifest, verification, fulfilled, fulfilledAt, current),
                current, warnings, blockers);
    }

    /** Views for the simple (possibly broken chain) path. */
    private List<EffectiveWorkflowState.StepView> views(WorkflowManifest manifest,
                                                        SignatureVerificationResult verification,
                                                        List<String> warnings) {
        boolean[] none = new boolean[manifest.stepsInOrder().size()];
        return views(manifest, verification, none, new Instant[none.length], null);
    }

    private List<EffectiveWorkflowState.StepView> views(WorkflowManifest manifest,
                                                        SignatureVerificationResult verification,
                                                        boolean[] fulfilled,
                                                        Instant[] fulfilledAt,
                                                        Integer current) {
        List<EffectiveWorkflowState.StepView> views = new ArrayList<>();
        List<SignatureVerificationResult.SignatureDetail>
                signatures = verification.signatures();
        for (int i = 0; i < manifest.stepsInOrder().size(); i++) {
            WorkflowSignerStep step = manifest.stepsInOrder().get(i);
            Instant when = fulfilledAt[i];
            views.add(new EffectiveWorkflowState.StepView(
                    step.order(), step.displayName(), step.certificate(),
                    fulfilled[i], fulfilled[i] && when != null,
                    when, current != null && current == step.order()));
        }
        return views;
    }

    private static int indexOfFingerprint(WorkflowManifest manifest, String fingerprint) {
        for (int i = 0; i < manifest.stepsInOrder().size(); i++) {
            if (manifest.stepsInOrder().get(i).certificate()
                    .matchesFingerprint(fingerprint)) {
                return i;
            }
        }
        return -1;
    }

    /** Short display helper for messages. */
    private static final class CertificateIdentityBrief {
        static String of(String fingerprint) {
            String hex = fingerprint.replace(":", "");
            return hex.length() <= 16
                    ? fingerprint
                    : hex.substring(0, 8) + "…" + hex.substring(hex.length() - 8);
        }
    }
}
