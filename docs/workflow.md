# Portable Cross-PC Signing Workflow — Technical Documentation

How a PDF signing workflow travels between computers while the cryptographic
truth stays entirely inside the PDF's own signature chain.

## 1. Architecture

```
JavaFX (WorkflowSelectionDialog → WorkflowContinuationDialog)
        ↓  renders EffectiveWorkflowState only — never decides authorization
WorkflowSigningService              ← one orchestrating service
  ├── WorkflowManifestStore         ← PDF embedded-file read/write
  ├── WorkflowManifestCodec         ← strict JSON, versioned, size-bounded
  ├── WorkflowReconciliationService ← manifest × signature chain → effective state
  ├── PdfSigningService             ← incremental revisions (existing)
  └── TokenService (PKCS#11)        ← keys never leave the token (existing)
```

The UI never parses certificates, never touches PKCS#11 and never decides
whether signing is allowed. All authorization flows through
`WorkflowSigningService.requireCanSignNow`, which re-reads the document from
disk on every attempt.

## 2. Workflow model

`WorkflowManifest` (schema version 1, `workflowManifestVersion`):

| field | meaning |
|---|---|
| `workflowManifestVersion` | schema version; >CURRENT is refused gracefully |
| `workflowId` | random UUID, stable for the document's life |
| `createdAt` / `updatedAt` | manifest timestamps (metadata only) |
| `steps[]` | ordered signer steps |

`WorkflowSignerStep`: `order`, `displayName` (metadata), `certificate`
(`CertificateIdentity`), `placement` (`SignaturePlacement`: page, x, y,
width, height in PDF points), `signedAt`, `revisionIndex`.

`CertificateIdentity` — the cryptographic identity of a step:

- `sha256Fingerprint` (colon-hex SHA-256 of the DER certificate) — the only
  matching component
- `subject`, `issuer`, `serialNumber` — display/audit only

Display names are never used for identity decisions.

`EffectiveWorkflowState` is the reconciled result: `WorkflowStatus`
(`NOT_STARTED`, `IN_PROGRESS`, `COMPLETED`, `SIGNATURE_INVALID`,
`MANIFEST_INCONSISTENT`), per-step `StepView`s (fulfilled, verified,
signedAt, yourTurn), `warnings` and `signingBlockers`. Signing is allowed iff
`signingBlockers` is empty and the connected token holds the current step's
certificate fingerprint.

## 3. PDF storage mechanism

The manifest is stored as a **PDF embedded file** in the document catalog's
EmbeddedFiles name tree under the name
`urn:signflow:workflow-manifest` (a standards-compatible mechanism; third
-party viewers ignore it or expose it as an attachment).

- **Before the first signature**: written by a full save (no signatures exist
  yet, so nothing can break).
- **At every signing**: the updated manifest is written *inside the same
  incremental revision as the new signature* (via the
  `PdfSigningService.DocumentCustomizer` hook). The new signature's byte
  range therefore covers the manifest state as of that signing — this is the
  tamper-evidence binding: any later unsigned edit of the manifest makes the
  **last** signature invalid, which reconciliation reports as
  `SIGNATURE_INVALID`. Nothing is appended after the last signature; the
  chain always reaches EOF.
- The codec enforces: version check before binding, strict unknown-field
  rejection, bounded sizes, safe failure (`ParseResult`, never a crash).

## 4. Cryptographic model

- Existing chain-aware verification is reused untouched. Each revision's
  signature must remain valid; the whole chain must verify.
- Signatures are attributed to workflow steps **only** by the signer
  certificate's SHA-256 fingerprint (now exposed on
  `SignatureVerificationResult.SignatureDetail`).
- Eligibility (`requireCanSignNow`) checks, in order: manifest present and
  supported → chain valid → blockers empty → current step exists → connected
  token is authenticated → token carries the step's fingerprint
  (`WORKFLOW_CERT_MISMATCH` otherwise).
- Identity never comes from filenames, display names or manifest status.

## 5. Cross-PC flow

1. PC 1: create workflow (signers, certificates, placements) →
   `writeManifestIntoCopy` embeds the manifest → sign step 1 → save.
2. Transfer the PDF by any means (email, USB, shared folder).
3. PC 2: "Open workflow…" → `load()` reads the manifest from the PDF itself,
   verifies the chain, reconciles → timeline shows who signed, whose turn →
   token unlock → fingerprint match → sign → post-sign verification →
   save (atomic).
4. Repeat for PC 3. Final PDF: all signatures valid, workflow `COMPLETED`,
   independently verifiable by any third-party PDF validator.

Crash safety: state lives in the PDF, never only in memory; reopening the
file reconstructs the workflow (covered by tests).

## 6. Security model

Protected cryptographically:

- document content per signed revision (byte ranges)
- who signed (certificate + fingerprint)
- manifest state **as of each signing** (inside the signed byte range)

Not cryptographically protected:

- the manifest **between** signatures — it is convenience metadata until the
  next signing binds it; whoever holds the file can alter names/placements
  between turns. Reconciliation detects overclaims (manifest says more
  signatures than the chain shows → `MANIFEST_INCONSISTENT`, signing
  disabled), but unsigned edits that stay consistent with the chain are
  reported in `warnings`, not treated as attacks.
- signer order/placement before the first signature is configuration.

Never stored, logged or serialized: PINs, private keys, token credentials.
The manifest contains public certificate data only.

## 7. Failure handling

| situation | behaviour |
|---|---|
| tampered document | `SIGNATURE_INVALID`, signing blocked |
| manifest claims > chain | `MANIFEST_INCONSISTENT`, signing blocked |
| wrong certificate on token | `WORKFLOW_CERT_MISMATCH`, PDF byte-identical |
| not your turn / completed | `WORKFLOW_OUT_OF_TURN` |
| corrupted manifest | parse failure surfaced, signing blocked, no crash |
| newer schema version | `WORKFLOW_UNSUPPORTED_VERSION`, read-only guidance |
| no manifest | normal-PDF path untouched |
| token missing / locked / bad PIN | existing `TokenException` handling |
| signing fails mid-way | temp file deleted, original untouched (atomic export) |
| file changed since displayed | decision always made on fresh disk bytes |

## 8. Testing

26 new tests in three suites:

- `WorkflowManifestCodecTests` (13): serialization, malformed input, versions
- `WorkflowManifestStoreIntegrationTests` (4): real PDF round-trip, signing
  with embedded manifest, manifest-inside-signing-revision
- `WorkflowCrossPcIntegrationTests` (9): the full A→B→C definition-of-done
  scenario over three real revisions (RSA/RSA/ECDSA), out-of-turn, mismatch,
  tamper, corruption, future version, stale-read, plain-PDF compatibility

Full suite: **116 tests, 0 failures** (90 pre-existing all preserved).

## 8b. Integrity hardening (document binding, DocMDP, placement bounds)

The portable workflow is bound to the exact bytes of the original document:

- `createWorkflow(pdf, …)` records the **SHA-256 of the original PDF** in the
  manifest (`originalDocumentSha256`, lower-case hex; absent for manifests
  created before binding existed — those keep working).
- `writeManifestIntoCopy` refuses to seed a bound manifest into any document
  whose bytes do not match the recorded hash (`WORKFLOW_TAMPERED`). This is
  the gate against preparing a workflow for one document and silently
  attaching it to another.
- After the first signature exists, the signature chain itself is the
  integrity anchor (every later signature cryptographically covers all
  earlier bytes, and the chain must reach end-of-file).

**Certification permissions (DocMDP).** Verification now extracts the DocMDP
access-permission value from certification signatures (per-signature
`/Reference` entries, plus the catalog `/Perms/DocMDP` fallback) and reports
it as `docMdp` on each signature detail. Reconciliation fails closed: a
document certified with permissions stricter than "form fill / signatures
allowed" (P≠2) cannot receive further workflow signatures — the UI shows a
certification blocker instead of silently violating the policy. P=2
certification (the standard approval-signing policy) is accepted.

**Placement bounds.** The signing service validates the placement rectangle
against the page's crop box at signing time and refuses out-of-page
placements (a manifest created against a different page size can no longer
draw a signature outside the document).

## 9. Known limitations

- Manifest metadata between signatures is trust-on-transfer (see §6); the
  pre-sign confirmation always shows the real certificate identity.
- No revocation checking (CRL/OCSP) and no trusted timestamps (RFC 3161) —
  signing time shown is the signature's claimed time. These are PAdES
  hardening candidates, independent of this feature.
- Two app instances editing one shared file are guarded by fresh-disk-read
  semantics, not file locking.
- Certificate **expiry is enforced at signing time** (fail closed); verbatim
  expiry status of *earlier* signers' certificates is displayed in the
  signature chain, but expired-but-valid-at-signing-time signatures are not
  distinguished (that requires trusted timestamps).

## 10. Future server migration

The domain layer is server-ready without rework: `WorkflowManifest` is plain
JSON, reconciliation is a pure function of (manifest, verification result),
and signing is a service call. A future server could store workflows and
documents, serve `load()` results, and relay signed revisions — the desktop
UI, signing engine and verification chain would be unchanged.
