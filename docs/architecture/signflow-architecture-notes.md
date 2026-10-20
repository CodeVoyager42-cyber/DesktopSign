# SignFlow – sequence diagrams and architecture notes

Status: October 2026. Everything marked **implemented** was checked against the source code
(`src/main/java/com/example/signer/**`, Flyway `V1`–`V4`). Everything marked **planned** does not
exist yet.

| File | What it shows | Status |
|---|---|---|
| `signflow-existing-sequence.mmd` | Remote signing as the code does it today | Implemented |
| `signflow-partner-integration-sequence.mmd` | An external application ("coworker's system") requesting a signature | Proposed |
| `signflow-multisigner-sequence.mmd` | The partner flow extended to three signers on separate PCs | Proposed |
| `*.svg`, `*.png` | Renders of the three sources (Mermaid 11.4) | Generated |

The `.mmd` files are the editable source of truth. To re-render, paste one into
<https://mermaid.live> or run `mmdc -i file.mmd -o file.svg` (mermaid-cli).

---

## 1. Current architecture (implemented)

### Components

| Component | Where | Role |
|---|---|---|
| SignFlow Desktop (JavaFX + Spring, no web server) | `ui/`, `client/` | Shows the PDF, places the stamp, talks to the card, signs, verifies, uploads |
| PKCS#11 layer | `token/pkcs11/` | `Pkcs11SigningTokenProvider` loads the vendor DLL through **SunPKCS11**; `Pkcs11WrapperAccess` reads token info and certificates **before login** (JDK-internal wrapper, needs `--add-exports`); `Pkcs11SigningToken` logs in (`AuthProvider`) and signs (`Signature` with the on-card key) |
| Token state | `token/TokenService`, `TokenStateMachine` | NO_TOKEN → … → CERTIFICATE_AVAILABLE → WAITING_FOR_PIN → AUTHENTICATED → SIGNING → SIGNATURE_COMPLETE/FAILED |
| PDF signing / verification | `signing/DefaultPdfSigningService`, `verification/DefaultPdfVerificationService` | PDFBox incremental update, CMS `adbe.pkcs7.detached`, SHA-256; the card signs the CMS signed attributes |
| Workflow domain | `workflow/` | Manifest embedded in the PDF, reconciliation (signatures outrank the manifest), local multi-signer engine |
| REST server (Spring Boot, profile `server`) | `server/**` | `WorkflowRestController` (`/api/workflows/**`), `AuthController` (`/api/auth/**`), `InvitationRestController` (`/api/invitations/**`), `SignPageController` (`/invitations/{token}`, alias `/sign/{token}`) |
| Security | `server/security/`, `server/auth/` | Spring Security protects every route by default (see `docs/security/phase-2-authentication.md`); HS256 access JWT (15 min); rotating refresh tokens with reuse detection, stored in the database; guest JWT limited by `GuestAccessPolicy` to one workflow and one slot |
| Workflow service | `server/workflow/RemoteWorkflowService` | Turns (SEQUENTIAL/FLEXIBLE + claim), certificate binding, optimistic concurrency, server-side validation, audit |
| Invitations | `server/invites/InvitationService` | 256-bit random token, **only its SHA-256 stored**, default expiry 48 h, resend/revoke, guest session |
| Database | PostgreSQL via JPA + Flyway | `users`, `workflows`, `workflow_signers`, `document_versions`, `audit_events`, `invitations` |
| PDF storage | `server/db/DocumentFileStorage` | One file per revision on the server's disk (`signature.remote-server.storage`); the DB keeps SHA-256 and size |

### Authentication actually in use

| Who | Mechanism | Scope |
|---|---|---|
| Desktop start-up gate | Local check of `signature.login.*` (`LoginDialog`) | **Not** a server login; it only gates opening the app |
| Account holder → server | `POST /api/auth/login` (bcrypt) → access JWT + refresh token | All of their workflows |
| Invited signer → server | `POST /api/invitations/{token}/guest-session` → guest JWT | One workflow, one signer slot, `GuestAccessPolicy` allow-list |
| Card | PKCS#11 `C_Login` with the PIN, entered in the desktop app | Local only; the PIN never leaves the PC |

### What the server checks on every upload (`submitSignature`)
1. The `signingOperationId` is new, or it's an idempotent replay.
2. The workflow is waiting for a signature, and it is this user's turn (or claimed turn).
3. `parentRevision` and `parentSha256` match the current revision.
4. The upload is a **pure incremental update**: it starts with the parent's exact bytes.
5. Every signature verifies, and there are exactly parent + 1 signatures.
6. The newest signature's certificate equals the **fingerprint bound** to the slot, and it is not a mock/development identity (outside dev).
7. Commit: immutable revision file, `document_versions`, audit events, then state
   `SIGNATURE_SUBMITTED → VALIDATING → WAITING_FOR_SIGNER | COMPLETED`.

A failed check returns an error and writes an audit event. **The workflow state does not change.**

---

## 2. Diagram A – existing flow (how to read it)
1. **Two entry paths.** An account holder logs in with a password and gets a JWT. An invited
   signer opens the email link: `/invitations/{token}` → `signflow://invitation/{token}` → preview →
   guest session.
2. **The task and the PDF** are fetched with the JWT. The desktop app re-verifies the existing
   signatures locally.
3. **Token detection** reads the certificates without a PIN. `SigningCardCheck` compares them
   with the fingerprint bound to the step.
4. **Optional binding.** While the workflow is in DRAFT, the signer registers their certificate
   fingerprint. The last binding prepares revision 0.
5. **PIN loop.** Wrong PIN → retry. Locked PIN → stop.
6. **Local signing.** The card signs the CMS signed attributes. The app embeds the signature
   and re-verifies the whole chain before uploading.
7. **Upload and server validation** as listed above. The success screen then lets the user view
   or download the signed PDF.

---

## 3. Diagram B – proposed partner integration

### Responsibilities
| Coworker's system | SignFlow |
|---|---|
| Decides which business action needs a signature | Creates and manages signing requests |
| Chooses the PDF and identifies the signer | Generates secure invitation links and launch codes |
| Calls `POST /api/partner/v1/sign-requests` from its **backend** | Opens the signing session in the desktop app |
| Receives webhooks, verifies HMAC, timestamp and event id | Talks to the card through PKCS#11 (locally) |
| Downloads the signed PDF and updates its own records | Verifies the signature (crypto + trust + revocation), stores it, notifies |

### Proposed Partner API (v1)
| Method and path | Purpose | Auth |
|---|---|---|
| `POST /api/partner/v1/sign-requests` | Create a request (multipart PDF + JSON metadata, `Idempotency-Key`) → `{id, status, expiresAt, signUrl}` | Partner credential |
| `GET /api/partner/v1/sign-requests/{id}` | Status (polling fallback) | Partner credential, own requests only |
| `GET /api/partner/v1/sign-requests/{id}/document` | Signed PDF, only once `SIGNED` | Partner credential, own requests only |
| `POST /api/partner/v1/sign-requests/{id}/cancel` | Cancel | Partner credential |
| `POST /api/launch/exchange` | Desktop exchanges a launch code for a signing-session JWT | The single-use code itself |

Request statuses: `PENDING → OPENED → SIGNED`, or `DECLINED | EXPIRED | CANCELLED`.

Webhook: `POST callbackUrl` with `{eventId, type, requestId, externalRef, occurredAt}` and the
headers `X-SignFlow-Timestamp` and `X-SignFlow-Signature: sha256=HMAC(secret, timestamp + "." + body)`.
It is sent from a **transactional outbox**, retried with exponential backoff, and processed
idempotently by `eventId`.

### Security boundaries (shown as notes in the diagram)
| Credential | Authenticates | Must never |
|---|---|---|
| Partner API credential | The coworker's **backend** | Reach a browser or the desktop app |
| User JWT / refresh token | A SignFlow account holder | Be written to logs or disk |
| Invitation token / launch code | Access to **one** signing request, re-checked on the server every time | Be trusted for anything else; the desktop app ignores URLs or ids carried in a deep link |
| Token PIN | Use of the local card | Leave the user's PC |
| Private key | The signature itself | Leave the USB token / smart card |

The other rules:
- A partner can only see its own requests and documents.
- `returnUrl` must be on that partner's allow-list.
- A request becomes `SIGNED` only after server-side verification, including trust and revocation.
- A reverse proxy or API gateway is optional infrastructure, not part of the design.

---

## 4. Diagram C – multi-signer extension (proposed)
- One workflow with three signer tasks.
- Each signer has their own invitation, launch code and signing session.
- **SEQUENTIAL:** the next signer is invited after the previous one is accepted.
- **PARALLEL:** all three are invited at once and may sign in any order. PDF signatures are still applied one on top of the other, so an upload based on an older revision gets `409` and the signer re-signs the latest one. The server already does this with `parentRevision`/`parentSha256` and FLEXIBLE claims.
- The workflow is `COMPLETED` only when every required task is valid.
- A decline rejects the workflow. An expired task can be resent. A failed upload leaves the task open for a retry.

The server already supports multi-signer workflows for its own users and invited guests. What is
new is creating them through the Partner API, with per-signer launch codes and webhooks.

---

## 5. Missing components for partner integration
1. Partner registry and credentials: a `partner_clients` table (name, hashed credential, scopes, webhook URL and secret, allowed return URLs) and a **partner authentication filter**. Since security Phase 2, Spring Security (`SecurityConfig`) covers every route; partner credentials would be a separate authentication there.
2. A `sign_requests` table (Flyway **V5**): partner id, `externalRef`, status, workflow id, original and signed SHA-256, callback and return URLs, expiry, idempotency key.
3. The `PartnerApiController` endpoints listed above, with ownership checks on every read.
4. **Launch codes**: short-lived, single-use codes, the `POST /api/launch/exchange` endpoint, and a desktop handler for `signflow://launch/{code}`. Today the long-lived invitation token itself is in the deep link.
5. **Certificate trust and revocation**: chain to a configured trust store (e.g. the Barid AC Racine roots) and OCSP/CRL checks. Today `TrustStatus` is `NOT_VERIFIED`.
6. **Webhook outbox and dispatcher**: an outbox table written in the same transaction as the status change, HMAC-SHA256 signing, retries with backoff, and a delivery log.
7. A "Back to …" button in the desktop app that uses an allow-listed `returnUrl`.
8. **Deployment**: public HTTPS, production profile, persistent JWT secret, and an installer that registers `signflow://` and contains the server URL.

---

## 6. Security issues and inconsistencies found
| # | Finding | Where | Suggestion |
|---|---|---|---|
| 1 | The desktop start-up "login" is a **local** check with default credentials `ISICOD`/`ISICOD` in code and `application.yml`. It looks like authentication but protects nothing, and it is unrelated to the server account. | `config/LoginProperties`, `ui/dialog/LoginDialog`, `application.yml` | Remove it, or replace it with the server login. At least, refuse to start with the default values outside dev. |
| 2 | The **raw invitation token** (valid 48 h by default) is in the email URL (`/invitations/{token}`, alias `/sign/{token}`) and in the `signflow://` deep link, so links end up in browser history, mail logs and process arguments. **Mitigated in security Phase 3:** the link alone no longer opens a guest session — a one-time code e-mailed to the invitee is required — and the sender no longer receives the link outside dev. | `InvitationService`, `InvitationLinks` | Optionally exchange the token for a short single-use launch code (diagram B). |
| 3 | **No trust or revocation validation.** Any certificate is accepted if it matches the bound fingerprint. | `verification/` (`TrustStatus.NOT_VERIFIED`) | Add a trust store and OCSP/CRL checks before `SIGNED`. |
| 4 | **Unbound slots use "first card wins".** Whoever opens the invitation binds whatever certificate they insert. Nothing checks that the certificate belongs to the invited person. | `RemoteWorkflowService.bindCertificate` | Match the certificate's subject or e-mail to the invitee, or let the sender pre-bind or approve. |
| 5 | **Partly fixed (security Phase 2):** `/api/auth/login` and `/register` are limited per client address and accounts lock after 5 wrong passwords. `/api/invitations/{token}/**` and the public `/invitations/{token}` page are still not throttled (their tokens are 256-bit random, so guessing is not practical). | `server/security`, `server/auth`, `server/invites` | Throttle the invitation routes too if they are exposed publicly. |
| 6 | **Fixed for database mode (security Phase 2):** refresh sessions are stored as hashes in `refresh_tokens` (V6) and survive restarts. File mode still keeps them in memory, and in dev the JWT secret is random per process, so a dev restart still logs everyone out. | `DbRefreshTokenStore`, `InMemoryRefreshTokenStore`, `JwtService` | Use database mode for any shared deployment; set `SIGNER_SERVER_JWT_SECRET` outside dev (already enforced). |
| 7 | The `FAILED` workflow state exists in the model and the DB constraint, but no code path sets it. A rejected upload leaves the state unchanged. | `WorkflowState`, `RemoteWorkflowService` | Either remove `FAILED` or define when it applies; document it either way. |
| 8 | Only the first invitation is e-mailed. Later signers are not told it is their turn. | `invites/` | Send a "your turn" notification (an outbox event). |
| 9 | There is no trusted timestamp, so the signing time comes from the signer's PC clock. | `signing/` | Add RFC 3161 timestamps (PAdES B-T) from a qualified TSA. |
| 10 | Plain HTTP is allowed in dev, and invitation links can use an auto-detected LAN `http://` address. | `InvitationLinkResolver`, profile `dev` | Production must use HTTPS only (see `docs/TRANSPORT-SECURITY.md`). |
| 11 | Single-instance hand-off listens on `127.0.0.1:61234`. Any local process can push an invitation token to the running app. The risk is low, since the server still checks the token. | `ui/SingleInstanceGuard` | Keep the user confirmation step; optionally add a per-session secret. |

---

## 7. Recommended implementation order
1. **Foundation:** HTTPS deployment, production profile, persistent JWT secret and refresh sessions, rate limiting.
2. **Trust and revocation validation.** Without it, "SIGNED" means "cryptographically valid" but not "issued by a trusted CA and not revoked". Partners will rely on it.
3. **Partner registry, credential and authentication filter, and the V5 migration** (`partner_clients`, `sign_requests`, idempotency keys).
4. **`POST sign-requests`, `GET status`, `GET document`**, reusing `RemoteWorkflowService.createWorkflow` and `InvitationService.invite` for one signer.
5. **Launch codes** and the desktop `signflow://launch/{code}` handler. Keep the current invitation link working as a fallback.
6. **Transactional outbox and webhook dispatcher**, with HMAC, retries and a delivery log.
7. **`returnUrl`** and the desktop "Back to …" button, plus decline propagation to the partner.
8. **Multi-signer partner requests** (diagram C).
9. **Installer and operations:** a jpackage MSI with protocol registration, monitoring, and a partner sandbox environment.
