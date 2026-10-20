# Remote (Server-Coordinated) Signing Workflow

How the existing desktop signing application became a multi-computer,
server-coordinated signing system — without giving up the local mode, the
PKCS#11 key protection, or the PDF integrity guarantees.

## 1. Roles (§42 of the task)

```
        SPRING BOOT SERVER (profile: server)
        workflow state · revisions · audit · authorization
                        |
      +-----------------+------------------+
      |                 |                  |
  COMPUTER A        COMPUTER B        COMPUTER C
  JavaFX desktop    JavaFX desktop    JavaFX desktop
  PKCS#11 token     PKCS#11 token     PKCS#11 token
  private key       private key       private key
  stays local       stays local       stays local
```

- **Server**: workflow state, signer sequence, revision store, validation of
  submissions, audit. It NEVER holds private keys, never sees a PIN, and
  never performs a cryptographic signing operation.
- **Desktop**: PDF review, local token authentication (PIN stays local),
  local incremental signing through the existing portable-workflow domain,
  local post-sign validation, upload.
- **Token**: private key + signing operation. The PIN never appears in any
  API call, log, or manifest.

## 2. Same artifact, two processes

| Process | Launch | Notes |
|---|---|---|
| Desktop (as before) | `./mvnw javafx:run` | Spring context stays `WebApplicationType.NONE` |
| Coordination server | `java -Dloader.main=com.example.signer.server.WorkflowServerApplication -cp pdf-signer-desktop.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.profiles.active=server --server.port=8080` | Servlet app on the same fat jar |

Storage defaults to `{java.io.tmpdir}/signer-server`; override with
`SIGNER_SERVER_STORAGE`. Workflow users (dev defaults) are
`alice`/`bob`/`charlie` (`SIGNER_USER_*` / `SIGNER_USER_*_PASS` env
overrides) — see `application.yml`.

## 3. Domain model (server package)

- `WorkflowState` — explicit state machine: `DRAFT → READY_FOR_SIGNING →
  WAITING_FOR_SIGNER → SIGNATURE_SUBMITTED → VALIDATING →
  WAITING_FOR_SIGNER (next) | COMPLETED`; `CANCELLED` from any active
  state; `FAILED` from `VALIDATING`. Illegal transitions throw; clients
  cannot set state.
- `WorkflowSignerRecord` — order, userId, **SHA-256 certificate fingerprint**
  bound at creation, signed flag, idempotency key of the accepted operation.
- `DocumentRevisionRecord` — 0-based contiguous revisions, parent link,
  SHA-256, size, author.
- `AuditEventRecord` — append-only trail (created/validated/rejected/
  completed …), no secrets by construction.
- `SigningWorkflowAggregate` — the owning aggregate: all mutations funnel
  through guarded methods (transition/markSigned/appendRevision/appendAudit).

## 4. REST API

All endpoints require HTTP Basic (`signature.remote-server.users`).
Errors use a machine-readable body `{"code","message"}` with proper status
codes (401/403/404/409/422).

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/workflows` | Create workflow (multipart: `request` JSON + `file` original PDF) → 201 |
| GET | `/api/workflows/my-tasks` | Workflows waiting for THIS user's signature |
| GET | `/api/workflows/{id}` | Workflow view (participants only) |
| GET | `/api/workflows/{id}/task` | Current task: revision, SHA-256, signer progress |
| GET | `/api/workflows/{id}/document` | Current revision bytes (current signer ONLY) |
| POST | `/api/workflows/{id}/signatures` | Submit signed revision (multipart, idempotent) |
| GET | `/api/workflows/{id}/revisions` | Revision history |
| GET | `/api/workflows/{id}/audit` | Audit trail |
| POST | `/api/workflows/{id}/cancel` | Cancel (participants, active states only) |

## 5. Submission protocol and validation

The client submits `{parentRevision, parentSha256, signingOperationId}` plus
the signed PDF. The server accepts only when ALL of the following hold:

1. authenticated user == the currently assigned signer (turn enforcement);
2. workflow state is `WAITING_FOR_SIGNER`;
3. `parentRevision` == the server's current revision number **and**
   `parentSha256` matches its stored hash (stale/branched revision
   rejection → `409 STALE_REVISION`);
4. the submission is a **pure incremental update**: byte-identical to the
   stored parent for the parent's whole length, then appended bytes
   (otherwise `422 DOCUMENT_MODIFIED`);
5. the full signature chain verifies through the same verifier the desktop
   uses (per-signature crypto, append-only containment, chain reaches EOF)
   and the signature count is exactly `parentRevision + 1`;
6. the NEW signature's certificate fingerprint matches the identity bound
   to the signer slot at creation (`422 CERTIFICATE_MISMATCH` otherwise);
7. `signingOperationId` has not been used before — a retry of the same
   operation returns the ORIGINAL accepted result without a second
   transition (idempotency, network-timeout safe).

On success: revision file written (immutable, refuse-overwrite), revision
record appended, signer marked signed, state advanced, audit events
written — all under the per-workflow lock. A failed validation never
touches the stored revision.

## 6. Desktop client

- `WorkflowApiClient` — JDK `HttpClient`, hand-rolled multipart, JWT bearer
  sessions (15-minute access token, rotating refresh token; Basic auth is gone),
  error translation. Per-user instance, not a singleton bean.
- `RemoteSigningService` — one remote step: task → download → **local**
  pre-sign validation → **local** PKCS#11 signing via the existing
  `WorkflowSigningService.signNextStep` (same domain path as local mode —
  no signing logic duplicated) → upload with idempotency key. The PIN is
  wiped immediately after `TokenService.authenticate` consumes it.
- `RemoteWorkflowDialog` — sign in as workflow user → "My Signing Tasks" →
  task detail with signer progress → PIN step → result. All network and
  signing work runs on background tasks.

Entry point: **"Remote workflow…"** on the workflow-selection screen.

## 7. Storage layout

```
{storage}/workflows/{workflowId}/
    state.json           aggregate snapshot (atomic temp+move writes)
    revisions/0.pdf      immutable, refuse-overwrite
    revisions/1.pdf
```

## 8. Verification

- 5 state-machine/aggregate unit tests
- 5 service-level integration tests: the full Alice→Bob→Charlie flow across
  three isolated sessions, stale-hash rejection, idempotent retry,
  participant authorization, out-of-turn rejection
- 3 HTTP-level tests against a real embedded server: 401 paths, multipart
  creation → task → document, per-signer download authorization, 404
- Full suite at time of writing: **133 tests, 0 failures**
- Live smoke test: packaged jar launched in server mode, real HTTP with
  curl — 401s, 201 create, 200 task/document/audit, 409 not-your-turn

## 9. Known limitations

- **Authentication is a static user table** (`signature.remote-server.users`)
  with HTTP Basic — appropriate for a LAN deployment, not for the internet.
  No per-user enrollment of certificates beyond the fingerprint in the
  creation request. TLS termination and a real user store are deployment
  concerns, deliberately out of scope.
- The creator supplies each signer's certificate fingerprint; there is no
  server-side verification of fingerprints against a directory at creation
  time (the enforcement point is submission time, which is the one that
  matters).
- No websocket/push: "My Signing Tasks" is fetch-on-open; the API is
  polling-friendly by design.
- `CANCELLED`/`FAILED` are terminal states but a blocked workflow's
  recovery (re-activation) is manual by design.
