# Security Phase D1 — website accounts (server side)

Status: implemented, **server side only**. Scope: how a visitor gets an
account, proves they control the address, recovers a forgotten password and
changes a known one — and how none of that becomes a way to discover who has
an account.

Follows `docs/architecture/website-launch-code-plan.md` §5 (Accounts). It
builds on the session and CSRF chain of Phase C
(`docs/security/phase-c-web-sessions.md`), and it resolves that phase's risks
**C2**, **C8** and **C9**. **There is still no frontend** — that is D2. These
endpoints are reachable and tested over real HTTP, but nothing serves a page.

The desktop login, guest invitations, launch codes and PKCS#11 signing are
untouched. `POST /api/auth/register` — the old invitation-bound registration —
is unchanged and still the only way an invited signer gets an account.

## 1. What was built

| Method | Route | Needs | Answer |
|---|---|---|---|
| POST | `/web/api/auth/register` | CSRF | **always 202**, except a password that breaks the rules → 400 |
| POST | `/web/api/auth/verify-email` | CSRF | 204, or one generic 400 |
| POST | `/web/api/auth/resend-verification` | CSRF | **always 202** |
| POST | `/web/api/auth/password-reset/request` | CSRF | **always 202** |
| POST | `/web/api/auth/password-reset/confirm` | CSRF | 204, or one generic 400 |
| POST | `/web/api/me/password` | CSRF + session | 204, or 401 |

The five that a visitor reaches before they can sign in are `permitAll` on the
`/web/**` chain — they need a CSRF token but no session, because the people
who need them are exactly the people who have no account, have not verified
it, or have forgotten the password. Being open costs nothing, because none of
them reveals anything (§3).

New files: `server/web/WebAccountController`, `server/web/WebAccountService`,
`server/web/WebSessionRegistry`, `server/auth/OneTimeTokens`,
`server/auth/SigningActionPolicy`, `server/security/IdentityRateLimiter`,
`server/invites/AccountEmailTemplate`, the two token entities and
repositories, and `V10__account_tokens.sql`.

### A deliberate deviation from the plan

Plan §5 says an unverified e-mail is **refused at sign-in** with the generic
error. That is not what was built. An unverified account **may sign in and
look around**; what it may not do is put its name on a document. The reason is
that refusing sign-in leaves the person with nowhere to go and no way to ask
for another link, while the thing actually worth protecting is the signature,
not the dashboard. See §5.

## 2. The one-time tokens

Both kinds are handled exactly like the invitation tokens (V2) and the launch
codes (V8):

| | |
|---|---|
| Value | 32 bytes (256 bits) from `SecureRandom`, base64url, 43 characters |
| Stored | **only** `SHA-256(token)`, lower-case hex, `CHAR(64)`, UNIQUE |
| Delivered | once, by e-mail, to the address in question — and then forgotten |
| Logged | never. The log says a link was sent, never what it was |
| Verification link life | **24 h** (`WebAccountService.VERIFICATION_HOURS`) |
| Reset link life | **30 min** (`WebAccountService.RESET_MINUTES`) |
| Uses | one. Consumed under `SELECT … FOR UPDATE` with `consumed_at IS NULL` |
| Superseded | issuing a new one sets `consumed_at` on every live token of that account |
| Shape check | `OneTimeTokens.looksLikeToken` before hashing, so a malformed submission costs nothing |

SHA-256 with no salt and no iteration count is right here and wrong for a
password: the input is 256 bits of uniform randomness, so there is nothing to
pre-compute or brute-force. Passwords go through bcrypt cost 12
(`PasswordHashing`), unchanged.

**A verification token records the address it was sent to**, separately from
the account. An address can change between issuing and clicking, and the token
proves the address it was *delivered to*, not whatever the account holds
later: if they differ, the token is consumed and verifies nothing.

**Changing a password retires outstanding reset links** (`applyNewPassword` →
`consumeAllForUser`), so a link e-mailed before the change cannot be used
after it.

## 3. No enumeration, anywhere

This is the point of the phase. Sign-up, resend and reset-request answer
**202 with an empty body** whatever happened — address free, address taken,
address unknown, account disabled, already verified, over the e-mail cap. The
only thing that differs is what arrives in the mailbox of whoever actually
controls the address.

Verification and reset confirmation answer 204, or **one** 400:

```json
{"code":"LINK_INVALID","message":"That link is no longer usable. Request a new one."}
```

Malformed, unknown, expired, already used, issued for an address that has
since changed, or belonging to a disabled account — all byte-identical. The
tests assert the bodies are equal rather than merely both 400.

Two things are deliberately **not** hidden:

- **The password rules** (`400`, "at least 8 characters" / "at most 72
  bytes"). They describe the request, not who exists.
- **A wrong current password** on `/web/api/me/password` (`401`). The caller
  is already signed in as that account, so it tells them nothing new.

A reset confirmation validates the new password **only after** the token is
known good, so the rules cannot be probed with a guessed token.

**Sign-up never touches an existing account.** A second registration for a
taken address does not change its password, display name or status; if that
account is still unverified it gets another verification link, which doubles
as a resend for its owner.

### What is in the log

Only: that a link was sent, for which *username*; that a verification or a
reset happened; that an e-mail was suppressed for being over a limit. Never a
token, never a link, never a password, never a session id. `WebSessionRegistry`
logs how many sessions it revoked, not which.

## 4. Revocation — Phase C risk C8, resolved

| Event | Website sessions | Desktop refresh tokens |
|---|---|---|
| Password **reset** (link) | **all** revoked | **all** revoked |
| Password **change** (signed in) | all **except the caller's own** | **all** revoked |

Sessions are **deleted** from `spring_session`, not flagged, so the cookies
that referenced them are dead on every server instance immediately. The
lookup is by `principal_name`, which V9 already indexes.

A reset ends every session because whoever asked for it may not be the person
who had the old password. A change keeps the browser doing the change signed
in, because signing someone out of the tab they are typing in is pointless
friction — but every *other* session goes.

## 5. The unverified gate

`UserAccountService.maySign(username)` is the seam, default `true`.
`DbUserAccounts` implements it as "active **and** `email_verified_at` is not
null". File-mode accounts have no address to verify, so they always may.

An unverified account may sign in, see `/web/api/auth/me` with
`emailVerified: false`, list its workflows, open a task and download the
document. What it may not do is commit its name to one. Every such action is
gated, each answering `403 EMAIL_NOT_VERIFIED`:

| Where | Gates |
|---|---|
| `RemoteWorkflowService.createWorkflow` | starting a signing request — in the service, so it holds for **every** caller path, including the website routes of Phase E |
| `AuthenticatedRequestFilter`, via `SigningActionPolicy` | the account-token requests: `POST …/claim`, `…/signatures`, `…/signers/{user}/certificate`, `…/launch-codes` |
| `LaunchCodeService` | issuing a launch code, exchanging one for a task session, and re-checking that session on **every** request (`taskSessionState` → `INVALID` → 401) |

`SigningActionPolicy` is an anchored path list in the style of
`TaskAccessPolicy`. It is checked in the filter rather than in the services
because that is where the **kind** of principal is known, which matters for
the exception below. Reading is deliberately absent from it.

**Guests are not subject to this.** A guest signing session has no account to
verify and proves control of its address by receiving an e-mailed code;
`GuestAccessPolicy` is what bounds it. Guests use `claim`, `signatures` and
the certificate route, so a gate placed in those services instead of the
filter would have stopped guest signing altogether.

Desktop **task sessions** (Phase B launch codes) are not in the filter's check
either, because `LaunchCodeService.taskSessionState` already re-evaluates the
same rule on every request of such a session — and answers 401, not 403, since
the whole session is void rather than one action refused.

## 6. Every limit, in one place

Three kinds of limit apply, and they are deliberately independent.

### Per client address — `LoginRateLimitFilter`

One shared singleton filter on both chains, `SIGNER_LOGIN_RATE_LIMIT` (default
**20**) requests per `SIGNER_LOGIN_RATE_WINDOW_SECONDS` (default **60 s**),
sliding, answering `429` with `Retry-After`. Separate **buckets**, keyed by
address plus a suffix, so a flood of one kind cannot lock the address out of
another:

| Bucket | Paths |
|---|---|
| sign-in (no suffix) | `POST /api/auth/login`, `/api/auth/register`, `/web/api/auth/login` |
| `\|launch` | `POST /api/desktop/launch` (Phase B) |
| guest (no suffix) | `POST /api/invitations/*/guest-code`, `*/guest-session` |
| **`\|account`** | the six Phase D routes of §1 |
| **`\|csrf`** | **`GET /web/api/csrf`** |

The CSRF bootstrap is the only limited `GET`: it creates a session row for an
anonymous visitor, so an unlimited flood would fill the session table. That is
Phase C risk **C9**, now closed from the other side as well (§7).

### Per recipient — `IdentityRateLimiter`

The address limit stops one machine; it does not stop a distributed flood
mailing one person over and over. So the endpoints that *send* are limited on
the **recipient** too, keyed by the lower-cased address:

| Limit | Value | Applies to |
|---|---|---|
| E-mails per address | **3** per **15 min** (`SIGNER_WEB_MAIL_LIMIT`) | register, resend-verification, password-reset/request |
| Token submissions per account | **10** per **15 min** | verify-email, password-reset/confirm |

Over the e-mail cap the answer is **still 202** and simply no e-mail is sent —
otherwise the cap itself would be an oracle. State is in memory, so it is per
instance and resets on restart; that is why the tokens are short-lived and
single use rather than relying on it (risk D3).

### Per account — unchanged

`DbUserAccounts` still locks an account for **15 min** after **5** wrong
passwords in a row, counted across both chains. Phase D adds no second
lockout and no second user store.

### The other rules

| Rule | Value |
|---|---|
| Password | ≥ **8** characters, ≤ **72** UTF-8 bytes, bcrypt cost **12** |
| Address | must contain exactly one `@`, not at either end, no spaces, ≤ **255** characters. Delivery is the real check, not a regex |
| Username | derived from the local part, non-`[A-Za-z0-9._-]` stripped, lower-cased, ≤ 80 characters, numeric suffix from 2 if taken |
| New account status | `ACTIVE`, `email_verified_at` null |

## 7. The anonymous session cap — Phase C risk C9, resolved

`GET /web/api/csrf` has to create a session before anyone signs in. Such a
session now gets a much shorter idle timeout than a signed-in one, and
**signing in promotes it**:

| | Idle timeout |
|---|---|
| Not signed in | **5 min** (`SIGNER_WEB_SESSION_ANON_IDLE`, `PT5M`) |
| Signed in | 30 min (`SIGNER_WEB_SESSION_IDLE`, unchanged) |

`ProductionSafetyValidator` refuses, outside `dev`, an anonymous timeout that
is zero, negative, or longer than the signed-in one: a visitor who never signs
in must not hold a session longer than one who did.

Together with the `|csrf` bucket, a visitor who only loads the page costs a row
for five minutes, and a flood is capped per address.

## 8. Database — V10

`V10__account_tokens.sql` creates **two new tables and nothing else**. No
existing table, column, row or index is dropped, altered or rewritten.

```
email_verification_tokens(id, token_hash UNIQUE, username → users, email,
                          created_at, expires_at, consumed_at NULL)
password_reset_tokens    (id, token_hash UNIQUE, username → users,
                          created_at, expires_at, consumed_at NULL)
```

- `token_hash CHAR(64)` — SHA-256 hex. **There is no column for a token.**
- `username` → `users(username)` `ON DELETE CASCADE ON UPDATE CASCADE`:
  removing an account cannot leave a usable link behind.
- `CHECK (expires_at > created_at)` on both: a link that dies before it is
  issued cannot be stored.
- Indexes on `username` (the supersede step and the throttles) and on
  `expires_at` (an eventual expiry sweep — not written yet, risk D5).

## 9. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SIGNER_WEB_MAIL_LIMIT` | `3` | Account e-mails per recipient per 15 min |
| `SIGNER_WEB_SESSION_ANON_IDLE` | `PT5M` | Idle timeout before sign-in |
| `SIGNER_LOGIN_RATE_LIMIT` | `20` | Per-address requests per window, per bucket |
| `SIGNER_LOGIN_RATE_WINDOW_SECONDS` | `60` | That window |

The links in the e-mails are built from `InvitationLinkResolver.baseUrl()`,
the same base the invitation links already use, as
`<base>/verify-email?token=…` and `<base>/reset-password?token=…`. **Those two
pages do not exist yet** (D2).

## 10. Test results

Full suite on **JDK 21.0.12.1 LTS** (the version the build targets) with a real
PostgreSQL 16.15.

```
Tests run: 564, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS — 5 min 45 s
```

| | After Phase C | After Phase D1 |
|---|---|---|
| Found | 532 | **564** (+32) |
| Passed | 524 | **556** |
| Failed | 0 | **0** |
| Errors | 0 | **0** |
| Skipped | 8 | **8** (unchanged) |

The 8 skips are the same ones as in Phase C — 6 POSIX/root-only in
`SingleInstanceGuardTests`, plus `RemoteWorkflowLiveE2ETests` and
`InviterDialogContractTests`, which need a live server on `:8080`.
**Phase D1 adds no new skips.**

New in this phase:

| Class | Tests | Covers |
|---|---|---|
| `WebAccountHttpTests` | 17 | sign-up, the taken-address case changing nothing, weak password, verify once and never again, unknown vs malformed vs **expired** (24 h) being identical, resend, the unverified gate on workflow creation, **the gate on all four signing actions** (and that reading is still allowed), reset identical answers, reset ending every session and refresh token, **an expired reset link** (30 min), password change keeping this session and ending the others, change needing the current password and a session, the anonymous/signed-in idle timeouts, nothing secret in the log |
| `SigningActionPolicyTests` | 5 | the four covered actions, that reading and non-POST methods are not covered, and that the match is anchored so no prefix or suffix smuggles a path past it |
| `WebAccountRateLimitHttpTests` | 3 | the per-recipient mail cap (still 202, no further mail), the `\|account` bucket reaching 429 with `Retry-After` **without spending the sign-in budget**, and the same for the `\|csrf` bucket |
| `AccountTokenMigrationLiveTests` | 5 | V10 on a fresh database, the exact columns (no token column) and the unique hash, the expiry CHECK, cascade from `users`, and that V10 leaves an existing **V9** database — rows and session rows — intact |
| `ProductionSafetyValidatorTests` | +2 | the anonymous idle timeout: longer than the signed-in one, and zero |

How the expiry tests age a link: they **move the token's timestamps backwards
in the test database** (`created_at` and `expires_at` together, because of the
CHECK) rather than sleeping for a day, then assert the answer is byte-identical
to an unknown token's.

Note on the rate-limit class: `LoginRateLimitFilter` is a shared singleton, so
its buckets are one piece of state for the whole class. The tests are
`@Order`ed — the one that needs an empty bucket runs before the one that fills
it — and they loop until the 429 rather than assuming an exact count, so they
do not depend on how much budget earlier assertions spent.

## 11. Residual risks

| # | Risk | Severity | Note |
|---|---|---|---|
| D1 | **Sign-up is open and creates a row before the address is proved** | Medium (abuse) | Decision D1 in the plan. Anyone can create unverified accounts at 20/min per address and 3 e-mails per recipient per 15 min. An unverified account can do nothing (§5), but the rows accumulate; there is no sweep and no CAPTCHA. |
| D2 | **E-mail delivery is the whole security of both flows** | Medium | A reset link in a mailbox is the account. Nothing here can fix a compromised or mis-delivered mailbox; the 30-minute life and single use are the mitigation. No second factor exists. |
| D3 | **Both identity limits are in memory, per instance** | Medium (availability) | Same limitation as the address limiter (Phase C risk C6). Behind several instances the effective caps multiply by the instance count. Moving them to the database or a shared cache is the fix. |
| ~~D4~~ | ~~Only workflow creation is gated on a verified address~~ | **FIXED** | §5. `SigningActionPolicy` + `AuthenticatedRequestFilter` now gate claim, signatures, certificate binding and launch-code issuing for account tokens, and Phase B's three existing `email_verified_at` reads were routed through `maySign` so there is one rule in one place. Guests are deliberately exempt. **What the original wording got wrong:** the launch-code path was never ungated — `issue`, `exchange` and `taskSessionState` each already read `email_verified_at` directly. The gap was the three account-token signing routes, and the duplication that hid it. |
| D5 | **Nothing deletes expired tokens** | Low | `deleteExpiredBefore` exists on both repositories and **is never called**; `idx_*_expires` is there for it. Expired rows are harmless (the expiry is checked on use) but they grow without limit. |
| D6 | **The sliding window is implemented twice** | Low | `IdentityRateLimiter` repeats `LoginRateLimitFilter`'s algorithm instead of sharing it. Deliberate — folding them together inside this phase would have changed the behaviour of the tested one — but it is duplication to retire. |
| D7 | **A reset does not end desktop *access* tokens, only refresh tokens** | Low | An access JWT already issued stays valid for up to its 15-minute life after a reset. Accepted: the same window Phase 2 accepted for a disabled account on the bearer chain. |
| D8 | **No notification when a password changes** | Low | Neither a reset nor a change e-mails the account to say so, which is the usual way a victim notices. Worth adding with D2. |
| D9 | **`__Host-`/`Secure` on localhost still unverified in real browsers** | Low (dev only) | Phase C risk **C1**, unchanged: this phase still has no frontend and no browser. D2 must resolve it and delete the dev cookie override. |
| D10 | **No CSP, and still no pages to apply one to** | — | Phase C risk C7, unchanged. D2. |
| ~~C2~~ | ~~No sign-up, verification or password reset~~ | **FIXED** | This phase. |
| ~~C8~~ | ~~Website sessions and desktop refresh tokens are independent on a password change~~ | **FIXED** | §4. |
| ~~C9~~ | ~~The CSRF bootstrap creates a session for an anonymous visitor~~ | **FIXED** | §7: 5-minute idle timeout before sign-in, promoted at sign-in, plus its own rate-limit bucket. |

## 12. What was deliberately not done

- **No frontend** — D2. The two pages the e-mails link to do not exist yet,
  so both links currently lead nowhere.
- No change-of-address flow: an account's e-mail cannot be changed at all.
  The verification token already carries the address it was sent to, so the
  flow has somewhere to build from.
- No "sign out everywhere" button and no session list (Phase C risk C4) —
  `WebSessionRegistry` now makes both straightforward.
- No account deletion, no admin routes, no role management.
- No expiry sweep (D5), no CAPTCHA (D1), no notification e-mail (D8).
- No workflow routes under `/web/api` — Phase E.
- No change to the desktop login, guest invitations, launch codes or signing.
