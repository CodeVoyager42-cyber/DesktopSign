# Website + desktop launch-code handover — audit and plan (Phase A)

Status: **proposal, awaiting approval.** No source file has been changed.
Audited against the code that includes security Phases 2, 2.1 and 3
(patch `signflow-security-phase3.patch`).

Goal: account and workflow management move to a React + TypeScript website;
the JavaFX app keeps only local PKCS#11 signing. The Spring Boot server stays
the single authority for identity, authorization and workflow state.

---

## 1. What exists today (verified in code)

| Area | Current implementation | Relevant for the website |
|---|---|---|
| Security chain | One Spring Security chain (`server/security/SecurityConfig`): stateless, bearer JWT only, CSRF off, explicit public list, everything else denied. `AuthenticatedRequestFilter` checks the account is still active / guest scope. | A cookie-based chain must be **separate**: different path prefix, CSRF on, never accepting bearer tokens; the bearer chain must never accept cookies. |
| Access tokens | HS256 JWT, 15 min, `typ` = `access` or `guest`; exp/iat required, lifetime bounded (Phase 2.1). | A third, narrower type (`task`) fits the same decoder. |
| Refresh tokens | Hash-only, `refresh_tokens` (V6), rotation, reuse revokes the family, logout. | Website does not use them. Desktop launched from the website will not need them either. |
| Accounts | `users` (V1+V5): status, role USER/ADMIN (unused), `email_verified_at`, lockout (5 / 15 min), per-address rate limit. BCrypt 12. | Reused as-is: one user store for website and desktop. |
| Registration | **Only invitation-bound** (`POST /api/auth/register` needs an invitation token). No open sign-up, no e-mail verification flow, no password reset, no password change. | All four must be built for the website. |
| Guests | Invitation link → e-mailed 6-digit code → guest JWT scoped to one slot (Phase 3). | Guests can keep this path, or do the code step on the website (decision D4). |
| Authorization | Owner / signer / current signer rules, uniform 404 for strangers, owner-only invitations and cancel (Phase 3, `AuthorizationMatrixHttpTests`). | Reused by the website controllers unchanged. |
| Signing | Desktop: task → document → local PKCS#11 → `POST /signatures`. Server accepts only if: caller is current signer, parent revision number (and hash, when sent) match, PDF is a pure incremental update of the stored parent, full signature chain verifies with the desktop's verifier, new signature's certificate = slot's bound fingerprint. Revisions are immutable files; every decision is audited; submissions idempotent by `signingOperationId`. | Unchanged. The launch code only replaces *how the desktop gets its session*. |
| Placement | Desktop already lets the signer choose / confirm the position (`RemoteSigningJourney`, overlap warning); server validates the accepted placement. | Unchanged. |
| Deep links | `signflow://invitation/<token>` only (`ui/InvitationLinks`). Launch argument parsed at start; a second launch forwards the token to the running instance through `SingleInstanceGuard`. | Must be extended with a versioned `signflow://launch?...` form. |
| Protocol registration | `scripts/register-signflow-protocol.cmd` (development, per-user `HKCU`). **The packaged installer (`scripts/package.sh` / jpackage) registers nothing.** | Installed copies cannot receive links today. |
| Desktop sign-in | Username/password in `RemoteWorkflowDialog` → `WorkflowApiClient.login`; tokens kept in memory only (`RemoteSessionCache`). Separate local start-up gate `LoginDialog` (ISICOD/ISICOD defaults). | Becomes optional; launch codes replace it for website users. |
| Web hosting | Server renders only the invitation pages. No static site, no CORS configuration (good: same origin is safest). | Serve the built React app from the same origin. |
| Schema | Flyway V1–V7. Additive migrations only. | New tables: website sessions, launch codes, e-mail/password tokens. |

## 2. Security findings that affect this work

| # | Severity | Finding | Where | Proposal |
|---|---|---|---|---|
| F1 | **High** | The single-instance channel is an **unauthenticated HTTP listener on a fixed port** (`127.0.0.1:61234`) that accepts the invitation token in the query string. Any local process can (a) **squat the port before SignFlow starts and receive the tokens** forwarded by later launches, or (b) push an arbitrary invitation into the running app. Web pages can also send simple GETs to localhost (browsers are only starting to restrict this). Launch codes forwarded this way would leak the same way. | `ui/SingleInstanceGuard` | Phase B: authenticate the channel with a per-instance random secret in a user-only file; POST body instead of query; reject requests carrying an `Origin` header; forward only to a verified listener. |
| F2 | Medium | Installed builds do not register `signflow://`. | `scripts/package.sh` | Phase F: register per-user at first run (HKCU, path of the installed launcher) or via the installer; verify the registered command points to the installed executable. |
| F3 | Medium | Another program can register itself for `signflow://` (per-user registry) and receive launch links. | Windows design | Short-lived single-use codes; re-check at exchange; show the user on the website which device / app version opened the session; no certificate binding through a launch session at all (Phase B review, H2). Stronger alternative: desktop-initiated pairing (device-code flow) — decision D3. |
| F4 | Medium | The desktop's reachability check logs in as `probe/probe`: every check is a failed login (rate-limit budget, log noise). | `ui/remote/RemoteWorkflowDialog` ~l.423 | Public `GET /api/health` (no auth) in Phase B. |
| F5 | Medium | Website needs CSRF protection; current chain disables CSRF (correct for bearer). | `SecurityConfig` | Separate chain with CSRF, see §4. |
| F6 | Info | No open registration, e-mail verification, password reset or change. | `server/invites/Registration*` | Phase D. |
| F7 | Info | `parentSha256` is optional on submission (the byte-prefix check still protects). | `RemoteWorkflowService.submitSignature` | Make it required for launch-code sessions. |
| F8 | Known | G2: the certificate is not matched to the invited person. | Phase 3 doc | Independent decision; launch codes do not make it worse when F3 mitigations apply. |
| F9 | Known | Local start-up gate with default ISICOD/ISICOD. | `config/LoginProperties` | Remove in Phase F once the website replaces desktop sign-in. |

## 3. Security domains (kept separate)

| Domain | Credential | Lives | Accepted on |
|---|---|---|---|
| Website session | `__Host-SF_SESSION` cookie (HttpOnly, Secure, SameSite=Lax) + CSRF token | server session store | `/web/api/**` only |
| Desktop account session | bearer access JWT (`typ=access`) + refresh token | desktop memory | `/api/**` |
| Desktop task session (new) | bearer JWT `typ=task`, one workflow + slot + user, ≤ 30 min, no refresh | desktop memory | the signing routes of `/api/workflows/{id}/…` only |
| Guest session | bearer JWT `typ=guest` after e-mailed code | desktop memory | same signing routes |
| Launch code (new) | 256-bit random, single use, ≤ 2 min | hash in DB | `POST /api/desktop/launch` only |
| Invitation token | 256-bit random in the e-mail link | hash in DB | invitation routes only |
| Partner API (future) | separate API keys, `/partner/api/**` chain | — | never on user routes |
| Smart-card PIN, private key | — | **the signer's PC only** | never sent anywhere |

## 4. Website authentication design

- **Server-managed sessions**, stored with Spring Session JDBC (survive
  restarts, work with several server instances). Cookie `__Host-SF_SESSION`:
  HttpOnly, Secure (outside `dev`), SameSite=Lax, Path=/. Session id rotated
  at login (fixation), idle timeout 30 min, absolute 8 h, invalidated at
  logout and when the account is disabled.
- **CSRF**: Spring Security `CookieCsrfTokenRepository` (readable `XSRF-TOKEN`
  cookie, `X-XSRF-TOKEN` header) on every state-changing `/web/api` request.
- **Two filter chains, split by `securityMatcher`**:
  1. `/web/**` — session + CSRF, JSON login endpoint, **bearer header ignored**.
  2. `/api/**`, `/invitations/**`, `/sign/**` — the existing stateless bearer
     chain, **cookies ignored**, unchanged rules.
  3. Static website files (`/`, `/app/**`, assets) — public, strict CSP.
- Same `UserAccountService`, lockout, rate limit and BCrypt as the desktop.
  No second user store.
- **Same origin**: the React build is served by Spring Boot (or by the same
  reverse proxy host). No CORS. In development Vite proxies `/web` to `:8080`.
- Nothing long-lived in `localStorage`; the SPA holds no token at all.

## 5. Proposed API contracts

All `/web/api` calls: cookie session + `X-XSRF-TOKEN` on POST/PUT/DELETE.
Errors keep the existing `{"code","message"}` shape.

### Accounts (Phase C/D)

| Method | Path | Body → Answer | Notes |
|---|---|---|---|
| GET | `/web/api/csrf` | → 204 + `XSRF-TOKEN` cookie | bootstrap |
| POST | `/web/api/auth/register` | `{email, password, displayName}` → 202 | always 202 (no account enumeration); verification e-mail; open sign-up per decision D1 |
| POST | `/web/api/auth/verify-email` | `{token}` → 204 | single use, 24 h, hash stored |
| POST | `/web/api/auth/resend-verification` | `{email}` → 202 | throttled |
| POST | `/web/api/auth/login` | `{username, password}` → 200 `{user}` | sets session; same lockout + rate limit; unverified e-mail refused with the generic error |
| POST | `/web/api/auth/logout` | → 204 | invalidates session |
| GET | `/web/api/me` | → `{username, email, displayName, role}` | |
| POST | `/web/api/auth/password-reset/request` | `{email}` → 202 | always 202; 1 h token |
| POST | `/web/api/auth/password-reset/confirm` | `{token, newPassword}` → 204 | revokes all sessions and refresh tokens |
| POST | `/web/api/me/password` | `{currentPassword, newPassword}` → 204 | revokes other sessions |

### Workflows (Phase E) — thin controllers over the existing services

| Method | Path | Notes |
|---|---|---|
| POST | `/web/api/workflows` (multipart) | same as `POST /api/workflows` |
| GET | `/web/api/workflows/owned`, `/participating` | existing queries |
| GET | `/web/api/workflows/{id}`, `/{id}/audit`, `/{id}/revisions` | existing rules (404 for strangers) |
| POST | `/web/api/workflows/{id}/invitations`, `/{id}/cancel` | owner only |
| POST | `/web/api/invitations/{id}/resend`, `/revoke` | owner only |
| GET | `/web/api/workflows/{id}/download` | **new**: final signed PDF once COMPLETED, owner and signers (gap G6) |

The website never downloads a document *for signing* and never submits a
signature.

### Launch-code handover (Phase B)

| Method | Path | Auth | Body → Answer |
|---|---|---|---|
| POST | `/web/api/workflows/{id}/launch-codes` | website session + CSRF | `{}` → 201 `{deepLink: "signflow://launch?v=1&c=<code>", expiresIn: 120}` |
| POST | `/api/desktop/launch` | none (code is the credential), rate limited per address | `{code, appVersion, device}` → 200 `{accessToken, tokenType:"Bearer", expiresIn, workflowId, signerOrder, documentName, minAppVersion}` |
| GET | `/api/health` | none | → 200 `{status, apiVersion, minAppVersion}` (replaces the `probe` login, F4) |

**Issuing** (`POST /web/api/.../launch-codes`): the server re-checks that the
session user holds a slot of the workflow and that the slot can sign now
(its turn, or FLEXIBLE and eligible). **Superseded by the Phase B security
review (H2):** a slot without a registered certificate gets 409
`CERTIFICATE_NOT_BOUND` — a launch never enrols a certificate, and a task
session cannot call the certificate endpoint (see
`docs/security/phase-b-launch-codes.md`). Otherwise the same 404 / 409 as the API. A new code for the same
user + slot revokes the previous unused one. Max 5 issued per user per minute.

**Code**: 32 random bytes from `SecureRandom`, base64url (43 chars). Only
`SHA-256(code)` is stored, in a new table:

```
launch_codes(id, code_hash UNIQUE, username → users, workflow_id → workflows,
             signer_order, purpose CHECK IN ('SIGN'), created_at, expires_at,
             consumed_at NULL, revoked_at NULL, consumed_by_device NULL,
             consumed_app_version NULL)
```

**Exchange** (`POST /api/desktop/launch`): one atomic statement —
`UPDATE launch_codes SET consumed_at = now() WHERE code_hash = ? AND
consumed_at IS NULL AND revoked_at IS NULL AND expires_at > now() RETURNING …`
— so two concurrent exchanges cannot both succeed. Then: account still
active, still slot holder, workflow state still allows the task, app version
≥ minimum (else `426 UPGRADE_REQUIRED`). Every failure (unknown, malformed,
expired, used, revoked, wrong scope) returns the **same** `400
LAUNCH_CODE_INVALID`. Audit: `DESKTOP_SESSION_OPENED` with device and version,
never the code.

**Task token**: JWT `typ=task`, claims `sub`, `wf`, `slot`, `lc` (launch-code
id), ≤ 30 min, no refresh (a new launch from the website if it expires).
Allowed routes = today's guest allow-list (`task`, `claim`, `document`,
`signatures`, own `certificate`) on that one workflow, enforced by the
existing `GuestAccessPolicy` generalised to `ScopedTaskPolicy`; revoked at once
if the launch code row is revoked.

**Deep link**: `signflow://launch?v=1&c=<code>`. Only the single-use code,
never a password, access token or refresh token. The page triggers it from a
button click (not from a URL the user can bookmark), and the code is consumed
in the first second of the app's start.

### Local-machine risks and mitigations

| Risk | Mitigation |
|---|---|
| Browser history | Custom-scheme navigations are not page history; the code is single use and expires in 2 min. |
| Process list (the link is a command-line argument) | Same; consumed immediately at start. |
| Second launch forwarding to the running app | Authenticated IPC (F1 fix). |
| Another app registered for `signflow://` (F3) | Single use + short expiry; the website shows "opened on device X, app vY at hh:mm" with a "this wasn't me" revoke; a task session can never bind a certificate (Phase B review, H2). Device-code pairing as an option (D3). |
| Replay | Atomic consume; tokens bound to the launch-code id. |
| Server impersonation | HTTPS outside dev (already enforced by `ProductionSafetyValidator`); the desktop only talks to its configured server URL, never to a URL taken from the link. |

## 6. Phased plan

| Phase | Scope | Main files | Tests |
|---|---|---|---|
| **B** | Launch codes + task token + desktop exchange; F1 IPC fix; F4 health endpoint; V8 `launch_codes`. Issuing is first exposed on `/api` for an *authenticated desktop or test client* so B is testable before the website exists. | new `server/launch/*` (service, controller, entity, repository), `JwtService` (`typ=task`), `security/*` (policy, rate limit paths), `V8__launch_codes.sql`, `ui/InvitationLinks`, `ui/SingleInstanceGuard`, `ui/SignerFxApplication`, `client/WorkflowApiClient` | atomic double-exchange, expired / reused / revoked / malformed / other-user / other-workflow codes, version check, scope of task token, IPC authentication, no code in logs |
| **C** | Website chain: sessions (Spring Session JDBC, V9), CSRF, JSON login/logout/me, chain separation | `security/WebSecurityConfig`, `web/auth/*`, `pom.xml` (spring-session-jdbc), `V9__…` | cookie never accepted on `/api`, bearer never on `/web`, CSRF required, fixation, lockout shared, logout |
| **D** | React + TS foundation (Vite, router, typed API client, CSRF handling, CSP), register / verify e-mail / login / reset / change password; server endpoints for those | `web/` (new frontend folder), `web/auth/*`, `V10__email_and_reset_tokens.sql` | server tests + frontend unit tests; no enumeration |
| **E** | Dashboard, create workflow, invitations, status, audit, final-PDF download | `web/workflows/*` controllers, React pages | authorization matrix repeated for `/web/api` |
| **F** | Website "Sign in SignFlow" button → launch code → desktop; protocol registration in the installer; then remove the desktop's account sign-in, workflow wizard and ISICOD gate (only after the website equivalents are verified) | React page, `scripts/package.sh`, desktop UI | end-to-end: website → link → desktop → mock card → server acceptance |

## 7. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| D1 | Who may create an account on the website? | Open sign-up with e-mail verification (simplest); alternative: invitation-only or an allowed e-mail domain list. |
| D2 | Where is the React app served from? | Same origin, built into the Spring Boot jar (one deployable, no CORS). |
| D3 | Launch protection against a rogue `signflow://` handler | Launch code + device/version notice + revoke (recommended now); device-code pairing later if required by your risk assessment. |
| D4 | Invited guests without an account | Keep the current desktop guest flow now; move the code step to the website in Phase E. |
| D5 | Keep the desktop's own username/password sign-in after Phase F? | Keep as a fallback until the website is in production, then remove. |
