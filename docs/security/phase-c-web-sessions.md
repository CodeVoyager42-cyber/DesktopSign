# Security Phase C — website sessions

Status: implemented, server side only. Scope: how the website authenticates a
visitor and how that is kept apart from the desktop's bearer tokens. **No
frontend exists yet** (Phase D); these endpoints are reachable and tested over
real HTTP, but nothing serves a page.

Follows `docs/architecture/website-launch-code-plan.md` §3–§6. The desktop
login, guest invitations, launch codes and PKCS#11 signing are untouched.

## 1. What a website session is

| | |
|---|---|
| Credential | the `__Host-SF_SESSION` cookie, and nothing else |
| Stored | server side, in PostgreSQL (`spring_session`, V9), via Spring Session JDBC |
| Accepted on | `/web/**` only |
| In the browser | nothing. No token is returned, so there is nothing to keep in `localStorage` |
| Idle timeout | 30 min, sliding (`SIGNER_WEB_SESSION_IDLE`) |
| Absolute timeout | 8 h from sign-in, regardless of activity (`SIGNER_WEB_SESSION_ABSOLUTE`) |
| Revoked when | the account is disabled or removed — re-checked on **every** request, then the session is invalidated |

Because the session lives in the database it survives a restart and works with
several server instances — unlike the desktop's in-memory refresh sessions in
file mode.

### The cookie

```
__Host-SF_SESSION=…; Path=/; HttpOnly; Secure; SameSite=Lax
```

- **`__Host-` prefix** — the browser itself then refuses the cookie unless it
  has `Secure`, has `Path=/` and has **no `Domain`**. A sibling subdomain
  cannot set it, and a plain-HTTP page cannot either.
- **`HttpOnly`** — script cannot read it, so an XSS bug cannot exfiltrate the
  session.
- **`SameSite=Lax`** — another site cannot make the browser send it on a
  cross-site POST. This is defence in depth *beside* the CSRF token, not
  instead of it.
- No `Domain` attribute is ever emitted.

## 2. Request flow

```
request to /web/**
  → session → SecurityContext    HttpSessionSecurityContextRepository
  → LoginRateLimitFilter         POST /web/api/auth/login: shared per-address budget, else 429
  → CsrfFilter                   state-changing methods need X-XSRF-TOKEN = the session's token
  → AbsoluteSessionTimeoutFilter session older than the absolute limit → invalidated, 401
  → WebAccountStatusFilter       account disabled or gone → session invalidated, 401
  → URL rules (WebSecurityConfig)
  → controller                   WebAuthController
```

The two session filters are chained explicitly — the account check is anchored
on the timeout filter rather than both being anchored on `CsrfFilter` — so
their order does not depend on insertion order.

| Route | Needs |
|---|---|
| `GET /web/api/csrf` | nothing (safe method; hands out the token) |
| `POST /web/api/auth/login` | CSRF token |
| `POST /web/api/auth/logout` | CSRF token; idempotent, 204 even with no session |
| `GET /web/api/auth/me` | a session (`ROLE_WEB_USER`) |
| anything else under `/web/**` | refused — nothing else is open yet |

## 3. Strict separation from the desktop domain

This is the point of the phase, so it is enforced structurally rather than by
a rule that could be forgotten:

| | |
|---|---|
| **Bearer on `/web/**`** | The website chain has **no** `oauth2ResourceServer` and no bearer token resolver, so an `Authorization` header is never even parsed. A valid desktop access JWT gets 401. |
| **Cookie on `/api/**`** | The existing chain is `STATELESS`, so its security-context repository never consults an `HttpSession`. A valid website cookie gets 401. |

Two chains, split by `securityMatcher`:

1. `@Order(1)` `WebSecurityConfig` — claims `/web/**`.
2. `@Order(2)` `SecurityConfig` — everything else, rules unchanged. It had to
   be given an explicit order because it matches any request, and Spring
   Security refuses a configuration where a catch-all chain is not last.

The website authority is `ROLE_WEB_USER`, deliberately not the `ROLE_USER` the
bearer chain uses, so the two can never be confused even if a future chain
were misconfigured.

## 4. One account store, one lockout, one rate limit

The website checks the same passwords against the same accounts as the
desktop. There is no second user store and no second set of counters.

| Layer | Shared how |
|---|---|
| Password check | the same `UserAccountService` (bcrypt cost 12) |
| Per-account lockout | `DbUserAccounts`, in the database — 5 wrong passwords in a row lock the account for 15 min, counted across **both** chains |
| Per-address rate limit | `LoginRateLimitFilter` is now a **shared singleton bean** added to both chains, and `/web/api/auth/login` is in its limited-path set |

The rate limiter being a singleton matters: two instances would be two
independent maps, so an attacker could get twice the attempts simply by
alternating between `/api/auth/login` and `/web/api/auth/login`.

### No enumeration

A wrong password, an unknown user and an invited-but-unregistered placeholder
all produce the byte-identical 401:

```json
{"code":"AUTHENTICATION_FAILED","message":"Wrong e-mail, username or password."}
```

The log records only a fingerprint of the identifier (`id#…`), never the typed
text — which is sometimes a password typed into the wrong field.

## 5. CSRF

The token is held **in the session** and mirrored into a script-readable
`XSRF-TOKEN` cookie that the website echoes in the `X-XSRF-TOKEN` header.
`GET /web/api/csrf` is the bootstrap and answers 204; it always re-publishes
the cookie, because a session that already holds a token would otherwise get
no cookie back and the website would have no way to read the value it must
send.

**Why not plain `CookieCsrfTokenRepository`.** It stores nothing, so
validation only compares the header of a request against the cookie of that
same request. Two consequences:

- **A token can never be retired.** Replaying an old token together with its
  old cookie still matches, so rotating it at sign-in would be cosmetic.
- **The expected value is client-supplied.** Anyone able to set a cookie on
  the host can choose both halves of the comparison.

`SessionBackedCsrfTokenRepository` makes the session the authority: a header
is accepted only if it matches the token *that session* is holding. Rotation
is therefore real, and an injected cookie proves nothing.

One implementation note worth keeping: the token must be stored as a concrete
`DefaultCsrfToken`. Spring MVC resolves a `CsrfToken` argument to a deferred
supplier wrapper, and putting that in the session fails to serialize, because
the session store writes attributes as bytes. The endpoint therefore reads the
token from the repository rather than taking it as a method argument.

**The plain `CsrfTokenRequestAttributeHandler` is used deliberately.** Spring
Security 6 defaults to `XorCsrfTokenRequestAttributeHandler`, which masks the
token per response; the cookie value would then not match the header the
website sends back, and every POST would fail. `setCsrfRequestAttributeName(null)`
makes the token resolve eagerly so the cookie is written on the bootstrap
request.

### Rotation at sign-in

A successful login issues a **fresh** token, for the same reason the session
id is rotated: the token a visitor arrived with may have been planted by
someone else. After login the previous token is refused — tested by replaying
both halves of the double submit on the new session and getting 403.

## 6. Session fixation

Login is a JSON endpoint, not Spring Security's `formLogin`, so nothing
rotates the session id for us. `WebAuthController.rotateSession` therefore
**invalidates any existing session and creates a new one** before the security
context is saved. The id a visitor arrives holding can never be the id they
end up authenticated on, and the old session is destroyed rather than merely
replaced. `sessionFixation().changeSessionId()` is configured as well.

## 7. Database

`V9__web_sessions.sql` adds `spring_session` and `spring_session_attributes`
and nothing else — no existing table, column or row is touched. The schema is
taken from spring-session-jdbc's own `schema-postgresql.sql` so its queries
match.

A session row holds the session id, its timestamps and its serialized
attributes (among them the authenticated principal). **No password, password
hash, bearer token or CSRF token is stored in a session.** Sign-out deletes
the row; attributes cascade with it.

Boot's `SessionAutoConfiguration` is statically excluded in
`application.yml`, so `WebSessionConfiguration` is the single path to a
session store: the desktop context and file mode never create one, and the
schema stays owned by Flyway instead of being created by Spring Session.

## 8. Configuration

| Variable | Default | Dev | Meaning |
|---|---|---|---|
| `SIGNER_WEB_SESSION_COOKIE` | `__Host-SF_SESSION` | `SF_SESSION_DEV` | Session cookie name |
| `SIGNER_WEB_SESSION_SECURE` | `true` | `false` | `Secure` attribute |
| `SIGNER_WEB_SESSION_IDLE` | `PT30M` | — | Sliding idle timeout |
| `SIGNER_WEB_SESSION_ABSOLUTE` | `PT8H` | — | Hard lifetime from sign-in |

`ProductionSafetyValidator` refuses, outside `dev`:

- a cookie name without the `__Host-` prefix,
- `secure-cookie=false`,
- an idle timeout that is zero, negative or over 12 h,
- an absolute timeout that is zero, negative or over 24 h,
- an absolute timeout shorter than the idle one,
- a timeout that is not an ISO-8601 duration.

### The `__Host-` on localhost question — NOT resolved

Development serves the site over plain `http://localhost`. The `__Host-`
prefix requires `Secure`, and a browser only accepts a `Secure` cookie from a
trustworthy origin. Current Chrome, Edge and Firefox treat `localhost` as
trustworthy, so the production cookie *probably* works there —
**but this was not verified against real browsers, because this phase has no
frontend and the work was done without a browser.** Rather than guess, the
`dev` profile uses an ordinary cookie name with no `Secure` attribute, which
works on plain HTTP everywhere, and production refuses exactly those two
values. **Phase D should check the production cookie in all three browsers
and, if it works on localhost, delete the dev override.**

## 9. Availability

The website chain is active only for the coordination server **in database
mode**, because the session store is PostgreSQL. In file mode `/web/**` is not
claimed by it and falls through to `SecurityConfig`'s `denyAll`: the website is
unavailable rather than unprotected.

## 10. Test results

Full suite on **JDK 21.0.12.1 LTS** (the version the build targets) with a
real PostgreSQL 16.

```
Tests run: 532, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS — 7 min 13 s
```

| | Before Phase C | After |
|---|---|---|
| Found | 501 | **532** (+31) |
| Passed | 493 | **524** |
| Failed | 0 | **0** |
| Errors | 0 | **0** |
| Skipped | 8 | **8** (unchanged) |

The 8 skips are the same ones as before — 6 POSIX/root-only in
`SingleInstanceGuardTests`, and `RemoteWorkflowLiveE2ETests` plus
`InviterDialogContractTests`, which need a live server on `:8080`.
**Phase C adds no new skips:** its tests run against a real PostgreSQL and a
real HTTP port, and are assumption-skipped only if PostgreSQL is unreachable.

New in this phase:

| Class | Tests | Covers |
|---|---|---|
| `WebSessionHttpTests` | 19 | cookie attributes, session stored in PostgreSQL, id rotation, CSRF required / matched / rotated at sign-in / `Secure`, chain separation both ways, logout, account disabled mid-session (C3), enumeration, lockout sharing, nothing secret in the log |
| `WebLoginRateLimitSharedTests` | 1 | the per-address budget is one bucket across both chains |
| `WebSessionMigrationLiveTests` | 4 | V9 on a fresh database, the exact schema Spring Session queries, attribute cascade, and that V9 leaves an existing V8 database intact |
| `ProductionSafetyValidatorTests` | +7 | every web-session rule in §8, plus that the defaults pass and that `dev` may use the plain-HTTP cookie |

The HTTP tests handle cookies **by hand** rather than with a `CookieManager`,
so the assertions see exactly what the server sent — including `Secure` on a
plain-HTTP test connection, which a browser would refuse to store but which is
what production must emit.

### A defect this phase's own suite caught

The first full run reported **0 failures but 43 errors**: every DB-mode test
context that runs with `webEnvironment = NONE` failed to start.
`WebAuthController` had the database condition but **not**
`@ConditionalOnWebApplication(SERVLET)`, so it was created in non-servlet
contexts where the servlet-only `WebSecurityConfig` — and therefore its
`SecurityContextRepository` bean — was absent. Both conditions must match the
configuration's exactly whenever a component depends on a bean that
configuration declares.

## 11. Residual risks

| # | Risk | Severity | Note |
|---|---|---|---|
| C1 | **`__Host-`/`Secure` on localhost unverified in real browsers** | Low (dev only) | §8. The dev override exists because this could not be tested here; Phase D should resolve it and remove the override. |
| C2 | **No sign-up, e-mail verification or password reset yet** | — (functional) | Phase D. Only accounts that already exist can sign in, and the verified-e-mail flag is reported but not enforced at sign-in. |
| ~~C3~~ | ~~Disabling an account does not kill its live website sessions~~ | **FIXED** | `WebAccountStatusFilter` now re-checks the account on every `/web/**` request, with the same two conditions the bearer chain applies (`knowsUser` and `isRegistered`). On failure the session is invalidated server-side — the row is deleted, so the cookie is dead on every instance — the context is cleared and the request gets 401. |
| C9 | **The CSRF bootstrap creates a session for an anonymous visitor** | Low | The price of server-side CSRF tokens (§5): `GET /web/api/csrf` writes a `spring_session` row before anyone signs in, and sign-in then replaces it. So a visitor who only loads the page costs a row until it expires, and an unauthenticated flood could create many. Mitigations if it matters: a shorter idle timeout for anonymous sessions, or rate-limiting the bootstrap. Not done — there is no public page yet. |
| C4 | **No "sign out everywhere" and no session list** | Low | Same index makes it straightforward. |
| C5 | **Absolute timeout is enforced per request, not swept** | Low | An idle over-age session stays as a row until the store's expiry sweep removes it. It cannot be used — the filter invalidates it on the next request — but it is still stored. |
| C6 | **Rate limit is per address, in memory, per instance** | Medium (availability) | Unchanged from Phase 2. Behind NAT or a proxy without a forwarded-address configuration, many users share one bucket. Now shared with the website, so a flood against either endpoint affects both — which is the intent, but it widens the blast radius of that known limitation. |
| C7 | **No CSP, and no pages to apply one to** | — | Phase D, together with the first served asset. |
| C8 | **Website sessions and desktop refresh tokens are independent** | Low | Signing out of the website does not end a desktop session and vice versa. Correct for now — they are separate domains — but a password reset must revoke both (Phase D). |

## 12. What was deliberately not done

- No frontend of any kind.
- No `register`, `verify-email`, `resend-verification`, `password-reset` or
  `me/password` endpoints — all Phase D in the plan's §5.
- No workflow routes under `/web/api` — Phase E.
- No change to the desktop login, guest invitations, launch codes or signing.
