# SignFlow (DesktopSign) — Project Knowledge Base

> Generated during an investigation pass (no code changes). Verify against source where cited.
> Facts below were checked in source; a handful of inferences are marked *(inferred)*.

## 1. Purpose and scope

SignFlow is a desktop-centric PDF digital-signing product built around PKCS#11 smart-card
tokens. One Maven artifact ships two applications selected by Spring profile:

- **Desktop app** (JavaFX, profile `desktop`/`dev`): local sequential signing by 1–3 signers,
  remote signing against a coordination server, chain-aware signature verification.
- **Coordination server** (Spring Boot servlet, profile `server`): accounts + JWT auth,
  workflows, invitations by email, guest signing sessions, desktop launch codes, the
  built-in website (Next.js static export), and the desktop installer download
  (`SignFlow-<version>.msi` + `.sha256` from `SIGNER_DOWNLOAD_DIR`) — the headline feature
  of this `feature-desktop-download` branch (docs/desktop-download.md §1–§6,
  `server/downloads/DownloadCatalog.java`, `server/downloads/DownloadController.java`).

Branch focus: making the website distribute the Windows installer, and making the website's
"Sign" button open the installed desktop app via `signflow://` deep links
(`packaging/windows/main.wxs`, `server/launch/LaunchCodeService.java#issue`).

## 2. Technology stack

| Layer | Choice |
|---|---|
| Language/runtime | Java 21 target (built locally on JDK 26.0.x too; see pom `jdk23-unsafe-memory-access` profile) |
| UI (desktop) | JavaFX 21.0.7, Ikonli 12.3.1 |
| Application container | Spring Boot 3.5.3 (`spring-boot-starter`, web, validation, security, oauth2-resource-server, data-jpa, mail, spring-session-jdbc) |
| PDF | Apache PDFBox 3.0.5 (incremental save, rendering, analysis) |
| Crypto | BouncyCastle `bcpkix-jdk18on` 1.80, SunPKCS11 (jdk.crypto.cryptoki add-exports) |
| Persistence | PostgreSQL (JPA/Hibernate, Flyway migrations `src/main/resources/db/migration` V1–V13) — all gated on `SIGNER_DB_ENABLED`; a file-based store is the fallback |
| Frontend | Next.js 15.5.4 + React 19.1 + TypeScript, Tailwind 4, Radix UI; static-exported into `src/main/resources/static` via `web/sync-to-server.mjs` |
| Build | Maven 3.9.11 wrapper; GitHub Actions CI (`.github/workflows/build.yml` → `mvnw verify`; `release.yml` → Windows MSI+zip on tag `v*`) |
| Packaging | jpackage + WiX 3.x via `scripts/package.sh`; fixed UpgradeCode `61FE853E-…`; `main.wxs.jdk-base` drift check against JDK 26.0.2 template |

Full env-var list: `.env.example` and `README.md` configuration table. Nothing else may be used to configure production (see `config/ProductionSafetyValidator.java`).

## 3. Repository map

```
src/main/java/com/example/signer/
  SignerApplication.java          desktop entry (launches JavaFX first)
  server/WorkflowServerApplication.java    server entry (profiles: server)
  ui/        JavaFX: SignerFxApplication, AppNavigator, SingleInstanceGuard (UDS hand-off),
             ActivationQueue, InvitationLinks (signflow:// parser), dialog/, workspace/
             (MultiSignerWorkspaceFlow…), remote/ (RemoteWorkflowDialog, NewWorkflowWizard,
             SigningTasksView, RemoteSigningJourney), kit/, support/
  client/    WorkflowApiClient (JDK HttpClient, JWT+refresh, endpoint list), RemoteSigningService
             (stages + idempotent submit), RemoteSessionCache, RemoteApiException
  workflow/  Portable manifest layer: WorkflowManifest(+Codec/Store), WorkflowSigningEngine
             (local state machine), WorkflowSigningService (embed manifest, signNextStep),
             WorkflowReconciliationService, SigningWorkflow/Signer/SigningStatus
  signing/   DefaultPdfSigningService (incremental revision + BouncyCastle CMS + visible stamp)
  verification/ DefaultPdfVerificationService (chain-aware, streaming 64 KiB hashing)
  pdf/       DocumentAnalysis, PdfboxPdfRenderService(+DocumentRenderer), metadata records
  token/     TokenService+TokenStateMachine; mock/ (dev P12), pkcs11/ (SunPKCS11 + wrapper bridge)
  server/    api/ (workflow + auth + invitations controllers), auth/ (JwtService, refresh
             rotation, deny list, accounts), invites/ (invitation lifecycle, guest codes,
             sign page, email), launch/ (launch codes for desktop hand-off), downloads/
             (installer catalog/controller), security/ (both filter chains, rate limiters),
             web/ (website session controllers + FrontendRoutingConfiguration),
             workflow/ (RemoteWorkflowService 1263 lines, aggregate, stores), db/
             (entities, repositories, DbPersistenceConfiguration, JpaWorkflowStore)
web/         Next.js source; app/ pages (sign-in, documents, document, send, open, download,
             account, verify-email, reset-password…), lib/ (api.ts is the only server client,
             dashboard/send/timeline/handoff/download.ts helpers + *.test.ts, 48 tests)
docs/        ARCHITECTURE-ASSESSMENT.md, desktop-download.md, TRANSPORT-SECURITY.md, workflow.md,
             architecture/ (sequence diagrams), security/ (phase 1–F write-ups)
packaging/windows/  main.wxs (full WiX replacement; registers signflow://), main.wxs.jdk-base
scripts/     package.sh, register/unregister-signflow-protocol.cmd, db-console.cmd, live-check.sh
postman/     SignFlow collections + rejection-matrix setup + PDF fixtures
run-app.cmd / run-server.cmd  Windows launcher scripts (dev profile; secrets file loader)
```

## 4. Startup and execution

- Desktop: `java -jar target/pdf-signer-desktop-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev`
  (run-app.cmd) → `SignerApplication.main` → `SignerFxApplication.init/start`
  → `SingleInstanceGuard.acquire` → boot Spring (`WebApplicationType.NONE`, profile `desktop`)
  → `AppNavigator` (login gate `LoginProperties` ISICOD/ISICOD → home dialog).
- Server: run-server.cmd → `{ JAR }` with `-Dloader.main=…WorkflowServerApplication`,
  profiles `server,dev`, port 8080; DB on by default; secrets read from
  `%SIGNFLOW_SECRETS_FILE%` or `%USERPROFILE%\signflow-secrets\mail-secrets.env`.
- Build: `./mvnw verify` (tests ~120 classes) → jar. Package: `scripts/package.sh` →
  `build/dist/SignFlow-0.1.0.msi` + `windows.zip` + `.sha256` sidecars.
  Publish: copy artifacts into `SIGNER_DOWNLOAD_DIR`; catalog is re-read per request.

## 5. Architecture in brief

Two filter chains (`server/security/SecurityConfig` bearer chain, `WebSecurityConfig`
order-1 web chain). JWT types: `access` (accounts, 15 min), `guest` (invitation-scoped ≤60 min),
`task` (launch-code session ≤30 min, no refresh, cannot bind certificates).
Refresh tokens rotate in one family (SHA-256 at rest; reuse revokes the family).
Access-token logout deny-list keyed by `jti`. Rate limiting DB-backed sliding windows
(`DbRateLimitStore`) shared by both chains.

Workflow state machine `server/workflow/WorkflowState`:
DRAFT → READY_FOR_SIGNING → WAITING_FOR_SIGNER → SIGNATURE_SUBMITTED → VALIDATING →
{WAITING_FOR_SIGNER | COMPLETED | FAILED/CANCELLED}. Policies: SEQUENTIAL and FLEXIBLE
(single persisted 30-min turn claim; first claim wins, second gets 409).
All mutations funnel through `RemoteWorkflowService` under `store.lockFor(id)` +
optimistic `workflows.version` (Flyway V3). PDF bytes always on disk under
`DocumentFileStorage` (`{storage}/workflows/{id}/revisions/<n>.pdf`), even in DB mode.

Stores: `WorkflowFileStore` (state.json, default) vs `db/JpaWorkflowStore`
(when `SIGNER_DB_ENABLED=true`) with one-way import (`FileStoreImporter`).
Users: `ConfiguredUserAccounts` (file mode) vs `DbUserAccounts` (bcrypt, per-address
lockout `login_failures` V13 + account-wide backstop 50).
Web sessions: Spring Session JDBC (V9), `__Host-SF_SESSION` cookie, CSRF in session.

## 6. Key flows (verified in source)

1. **Local signing**: `MultiSignerWorkspaceFlow` → `WorkflowSigningEngine.sign` →
   `DefaultPdfSigningService.signPdf` (incremental save; CMS signed attrs; adbe.pkcs7.detached)
   → `DefaultPdfVerificationService.verifyPdf` re-verifies whole chain after every signer.
   Private key never leaves token; PIN handled as char[] and wiped.
2. **Remote signing (desktop)**: login → tasks → claim → download revision →
   `RemoteSigningService.signRemoteStep` (stale-revision check before token unlock) →
   sign → `submitIdempotently` (same `signingOperationId`+bytes, 3 attempts on transport
   failure, server dedups by operation id in `RemoteWorkflowService#submitSignature`).
3. **Guest signing (no account)**: `/invitations/{token}` public page → guest-code email
   (6-digit, V7 columns) → guest JWT → allow-listed actions only (`server/security/GuestAccessPolicy`).
4. **Website → desktop hand-off (Phase B/F)**: website `POST /web/api/workflows/{id}/launch-codes`
   → `signflow://launch?v=1&c=<43-char code>` (120 s TTL, store = SHA-256, V8) → OS scheme
   handler → `SignerFxApplication` → `SingleInstanceGuard` UDS hand-off →
   `LaunchDialog` → `POST /api/desktop/launch` exchange → task JWT → signing journey.
   Desktop version gate → HTTP 426 upgrade prompt (`server/launch/DesktopVersion`).
5. **Installer download (branch feature)**: `/download` public page →
   `GET /web/api/download-info` (public, rate-limited) → `DownloadCatalog` picks newest
   `SignFlow-<version>.msi`/`-windows.zip` matching strict regex, reads `.sha256` sidecar
   (≤4 KiB) → `GET /downloads/{file}` streams with Range support; unset dir = unavailable,
   set-but-missing dir fails startup; symlink/traversal defenses verified
   (`DownloadCatalog.java#resolve`, `toRealPath` check).

## 7. Configuration (variable names only)

`SIGNATURE_LOGIN_USERNAME|PASSWORD|SERVER_USER|SERVER_PASSWORD`, `SIGNATURE_PKCS11_ENABLED`,
`PKCS11_LIBRARY`, `PKCS11_SLOT_INDEX`, `SIGNATURE_VERIFICATION_MAX_FILE_BYTES`,
`SIGNER_SERVER_URL`, `SIGNER_UPLOAD_MAX_BYTES|REQUEST_BYTES`, `SIGNER_SERVER_JWT_SECRET`,
`SIGNER_SERVER_STORAGE`, `SIGNER_DB_ENABLED|URL|USERNAME|PASSWORD`, `SIGNER_DOWNLOAD_DIR`,
`SIGNER_DOWNLOAD_RATE_LIMIT|WINDOW`, `SIGNER_INVITATION_*`, `SIGNER_PUBLIC_BASE_URL`,
`SIGNER_GUEST_VERIFICATION`, `SIGNER_DESKTOP_MIN_VERSION|TASK_MINUTES`,
`SIGNER_WEB_SESSION_*`, `SIGNER_WEB_SIGNUP_*`, `SIGNER_WEB_UNVERIFIED_RETENTION`,
`SIGNER_EMAIL_MODE|FROM`, `SPRING_MAIL_*`, `APP_BASE_URL`, `APP_NAME`,
`SIGNER_TLS_OFFLOADED`, `SIGNER_FORWARD_HEADERS_STRATEGY`, `SIGNER_LOG_FILE`,
`SIGNER_MOCK_TOKEN_KEYSTORE`, `SIGNFLOW_DEV`, `SIGNER_DOWNLOAD_URL`, `SIGNER_USER_ALICE|BOB|CHARLIE` (dev).

## 8. Testing & build commands

- Java tests: `./mvnw test` (per-module classes mirror main packages; live tests skip when
  Postgres unreachable — tests run under `dev` profile with mock token keystore in `target/`).
- Full build: `./mvnw verify` → Spring Boot jar (+ signflow-build.properties filtering).
- Web: `npm run lint`; `node --test "lib/*.test.ts"` (48 tests); `npm run sync` (build + copy
  static export into `src/main/resources/static` — committed deliberately).
- Package: `scripts/package.sh [-–skip-build]` (needs WiX 3.x for MSI; regenerates icons via
  `tools/BuildIcon.java`; diffs `main.wxs` against JDK template).
- Postman: `postman/*.postman_collection.json` (incl. rejection matrix).

## 9. Conventions

- Constant-time comparison in `LoginProperties`; only JDK HttpClient (no RestTemplate/WebClient).
- Angular-style strict CSP: inline scripts only via hash (`InlineScriptHashes`), no CORS anywhere,
  no SPA catch-all (honest 404s, `FrontendRoutingConfiguration.PAGES` is the single page list).
- Every new page must be added to `web/app/<page>/page.tsx`, static-exported, listed in
  `FrontendRoutingConfiguration.PAGES`, and get `<page>.html` permitted (`SecurityConfig`).
- Error model: `SignerException` + `ErrorCode` → HTTP JSON `ApiError{code,message}`;
  client maps known codes (`RemoteApiException`), UI shows raw text only for app-authored
  messages (`ui/support/ErrorMessages`).
- Secrets: never in config files, `.gitignore` excludes `*.env` except `.env.example`;
  server logs use `SecretRedaction`.

## 10. Known issues / debt (observed)

- FIXED (feature-desktop-download): `run-app.cmd`/`run-server.cmd`/`register-signflow-protocol.cmd`
  no longer fall back to the machine-specific path (`C:\Users\Mnl\.jdks\openjdk-26.0.2`); all
  three now `call scripts\dev-runtime.cmd`, which resolves JAVA 21+ (JAVA_HOME → PATH →
  `%ProgramFiles%` roots) and the newest `target\pdf-signer-desktop-*.jar`. Guarded by
  `LauncherScriptsTests` (com.example.signer). Note the batch pitfalls that shaped that file:
  quoted exe paths break inside `for /f` command strings (parse via temp file), pipes leave
  trailing spaces (breaks `$`-anchored findstr), `if cond & A` runs A unconditionally, and
  multi-line `if (...)` blocks containing `goto` misbehave on some cmd versions.
- `scripts/package.sh` APP_VERSION is now derived from the built jar name (no hardcoded
  `0.1.0`); jpackage gets `INSTALL_VERSION` (the numeric part, e.g. `0.1.0`) because it
  validates `--app-version` as numeric. JAVA_HOME fallback scans well-known roots for
  `bin/jpackage` (the old `dirname $(command -v java)` picked the Oracle javapath shim).
- REPORT-ONLY: `db-console.cmd` hardcodes `C:\Program Files\PostgreSQL\16\bin`;
  register-signflow still registers a working-but-broken handler when no jar exists yet
  (pre-existing, documented behavior); CI (.github/workflows) builds on JDK 21 while the
  WiX drift base (`packaging/windows/main.wxs.jdk-base`) is a JDK 26.0.2 template —
  drift check on runner JDK 21 may warn (re-validate before bumping the base).
- Same jar boots desktop and server; `@SpringBootApplication(scanBasePackages="com.example.signer")`
  makes token/PDF beans exist in the server context (unused but scanned).
- Javadoc mismatch: `WorkflowServerApplication` comment says "exposing only
  `/api/workflows/*`" (stale — many more routes exist).
- Packaged MSI is not code-signed (documented decision, docs/desktop-download.md §1).
- `reports/database-overview.html` is a generated artifact committed in the repo.
- `web/package.json` lacks `"type": "module"` (node --test warns; tests still pass).

## 11. Open questions

- Postgres for local dev (`Signflow` db on localhost:5432)?
- CI JDK 21 vs `main.wxs.jdk-base` pinned to a JDK 26.0.2 template: which JDK should the
  release pipeline standardize on (drift check needs alignment)?
- Are any deployments running "file mode" in production, or is DB mode measured as the only mode?
