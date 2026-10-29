# Architecture Assessment — Remote Multi-Signer Signing Platform

Phase 0 deliverable. Inspection of the existing codebase **before** the
PostgreSQL / invitation / persistence migration. Every statement below was
verified against the source on this branch.

---

## 1. Current architecture

One artifact (`pdf-signer-desktop`, Spring Boot 3.5.3, Java 21), two run modes:

| Mode | Entry | Profile | Notes |
|---|---|---|---|
| JavaFX desktop | `SignerApplication` / `SignerFxApplication` | (none) | `WebApplicationType.NONE` |
| Coordination server | `WorkflowServerApplication` | `server` | servlet app, same jar (`run-server.cmd`) |

## 2. Client (JavaFX) — reusable as-is

- Screens: `LoginDialog` (start-up gate, fixed account `ISICOD`/`ISICOD` via `LoginProperties`), `WorkflowSelectionDialog`, `RemoteWorkflowDialog` (remote sign-in → my tasks → task detail → claim → PIN → sign → result, with internal Back history), `SigningWorkspace` (visual placement on rendered pages), `MultiSignerWorkspaceFlow` (local multi-signer), `WorkflowContinuationDialog` (portable manifest PDFs). Back-navigation history was just added.
- HTTP: `WorkflowApiClient` (JDK HttpClient, JSON via Jackson), login → JWT access+refresh bearer calls.
- PKCS#11: `TokenService` state machine (NO_TOKEN → CERTIFICATE_AVAILABLE → WAITING_FOR_PIN → AUTHENTICATED → …), `Pkcs11SigningToken` (generic PKCS#11, slot enumeration, certificate selection, local PIN), `MockSigningToken` for dev (stable seeded identities). PIN and private key never leave the client — verified in code paths.

## 3. Server — reusable as-is

- **Auth** (`server/auth`): `JwtService` (HS256, minimal claims: sub/iss/aud/iat/exp/jti/typ, 15-min access TTL), Spring Security filter chain in `server/security` (every route protected by default, structured 401/403; replaced `JwtAuthFilter` in security Phase 2), `RefreshTokenStore` (database or in-memory, 256-bit tokens, SHA-256-at-rest, rotation with family reuse detection), `AuthController` (`/api/auth/login`, `/refresh`, `/logout`, `/me`). Secrets are never logged; unknown-user and wrong-password are indistinguishable.
- **Workflow engine** (`RemoteWorkflowService` + `SigningWorkflowAggregate`): explicit `WorkflowState` machine (DRAFT → READY_FOR_SIGNING → WAITING_FOR_SIGNER → SIGNATURE_SUBMITTED → VALIDATING → COMPLETED/FAILED/CANCELLED, guarded transitions), SEQUENTIAL + FLEXIBLE policies with persisted 30-min turn claims, certificate binding (UNBOUND → BOUND, fingerprint SHA-256), idempotent submissions (`signingOperationId`), stale-revision rejection (parent number **and** SHA-256), incremental-update byte-prefix verification through the same verifier the desktop uses. Audit types already exist (`AuditEventType`, append-only per workflow).
- **Persistence** (`WorkflowFileStore`): filesystem JSON aggregate snapshots (`state.json`, atomic rewrite, per-workflow lock) + append-only revision files (`revisions/N.pdf`, write-once) + staged originals for DRAFT. SHA-256 per revision. **This is the only persistence seam** — `RemoteWorkflowService` touches nothing else (plus one leak: `workflowsDir().listFiles()` for `myTasks` listing).
- **REST** (`WorkflowRestController`): create (multipart), get, my-tasks, task, claim, eligible-signers, bind-certificate, document download, revisions, audit, submit-signature (multipart), cancel — all authorized via JWT-derived user.

## 4. PDF layer — reusable as-is

PDFBox 3.0.5 + BouncyCastle 1.80. `PdfSigningService` (CMS, incremental updates, visible signature appearance, placement in PDF coordinates via `SignaturePlacement`/`CoordinateMapper`), `PdfVerificationService` (full chain incl. previous signatures), `WorkflowManifest`/`WorkflowManifestCodec` (hardened schema-controlled manifest embedded in PDFs).

## 5. What is MISSING vs. the target architecture

> Historical: this table records the gaps found at the time of the original
> assessment. Most have since been closed (database, Flyway, accounts,
> invitations, email, registration). For the current security state see
> `docs/security/phase-1-identity-foundation.md` and
> `docs/security/phase-2-authentication.md`.

| Gap | Detail |
|---|---|
| Database | None. Users in YAML config (`RemoteServerProperties.users`), workflow state in JSON files, refresh tokens in memory. |
| Flyway | None. |
| User entity | None — usernames are config strings; no email, no status, no password hashing per user (single shared map). |
| Invitations | None — signers are chosen by typing user ids + certificate fingerprints. |
| Email | None. |
| Registration | None — `/api/auth/register` does not exist. |
| Document versions | Partial: `DocumentRevisionRecord` per workflow (number/parent/sha256/size/createdBy) — maps cleanly to a DB table; files already stored correctly. |
| Signature placement persistence | Placement happens client-side in the signing workspace and is embedded in the signed PDF (visible signature), but the workflow-signer record does not persist per-signer placement metadata. |
| Audit persistence | Audit lives inside the workflow snapshot; no global queryable event store. |

## 6. Migration strategy (chosen)

Incremental, behind a feature gate — nothing breaks until switched on:

1. Extract `WorkflowStore` interface from `WorkflowFileStore` (load/save/lock/listing + staged/revision byte ops). `RemoteWorkflowService` depends on the interface.
2. Add JPA + Flyway + PostgreSQL driver; **all DB beans gated on `signer.remote-server.database.enabled`** so the default (file-based) mode keeps working and the desktop context never touches a DataSource.
3. `JpaWorkflowStore`: same aggregate snapshot mapped to relational tables (`workflows`, `workflow_signers`, `document_versions`, `audit_events`); PDF bytes stay on disk under the existing directory layout via a `DocumentFileStorage` component (blob path unchanged, object-storage-ready).
4. `UserAccountService` seam for login: YAML accounts (default) vs. DB accounts (bcrypt, email+username, status) when DB enabled; dev seed alice/bob/charlie (dev-only, gated by the existing `allow-plaintext-passwords` dev flag).
5. Invitations + `EmailService` (console default, SMTP optional) + invitation-bound registration (`POST /api/auth/register` with token; email taken from the invitation server-side, never from the client).
6. Existing file-mode tests stay green; new tests run against the real PostgreSQL 16 already installed on this machine (assumption-skipped when it is unreachable).

## 7. Risks

- **Desktop context must never boot JPA**: handled by static auto-config exclusions + manual conditional `DbPersistenceConfiguration`.
- **Store listing leak** (`listFiles`) must move behind the interface or `myTasks` breaks on JPA.
- **Free-text actor/userId strings** (`"system"`, usernames) — FKs only where the row is a real account (signers, claim holder); audit actors stay provenance text.
- **Runtime server is a locked jar** — a server rebuild requires stopping the running server process first (Windows file lock), coordinated with the user.
