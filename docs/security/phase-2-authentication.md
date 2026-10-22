# Security Phase 2 — authentication

Status: implemented. Scope: how the server authenticates requests and
manages sign-in sessions. Authorization rules inside the services (who may
see or sign which workflow), partner credentials, and the desktop's token
storage are later phases.

## Request flow

```
request
  → LoginRateLimitFilter      POST /api/auth/login|register: max N per address per window, else 429
  → BearerTokenAuthentication Spring Security: Authorization: Bearer <jwt>
                              HS256 only · signature · iss · aud · exp (no clock skew) · typ
  → AuthenticatedRequestFilter account token: account must still be able to sign in, else 401
                              guest token: GuestAccessPolicy + invitation still active, else 403
  → URL rules (SecurityConfig) public list · /api/workflows/** user or guest · /api/** user · rest denied
  → controller                AuthController.requireUser(request)
```

| Route | Needs |
|---|---|
| `POST /api/auth/login`, `/refresh`, `/logout`, `/register` | nothing (a bearer header there is ignored) |
| `GET /api/invitations/{token}`, `POST /api/invitations/{token}/guest-session` | nothing; the invitation token is checked by the service |
| `GET /invitations/{token}`, `/sign/{token}`, `/sign/{token}/document` | nothing (pages behind the emailed link) |
| `/api/workflows/**` | account token, or a guest token for its own task only |
| any other `/api/**` | account token |
| anything else | refused |

Responses: `401 {"code":"AUTHENTICATION_REQUIRED"}` with `WWW-Authenticate: Bearer`
for a missing or bad token (the reason is logged, never returned);
`403` for a valid token without the permission. No cookies, no HTTP session.

## Sign-in sessions

- Access token: JWT, 15 minutes, issued and checked with Spring Security's
  Nimbus support (`jjwt` removed). Same claims as before, so existing
  desktop clients keep working.
- Refresh token: 256-bit random, rotated on every use. Database mode stores
  only its SHA-256 hash in `refresh_tokens` (V6), so sessions survive a
  restart. Reusing a replaced token revokes the whole sign-in. Logout revokes
  the sign-in. Rotation locks the row, so two simultaneous refreshes cannot
  both succeed. File mode keeps them in memory, now with the same guarantee.
- Disabled or removed account: refused on its next request, and its refresh
  sessions are revoked on the next refresh.

## Brute-force protection

| Layer | Default | Setting |
|---|---|---|
| Per client address, login and register | 20 requests / 60 s, then `429` + `Retry-After` | `SIGNER_LOGIN_RATE_LIMIT`, `SIGNER_LOGIN_RATE_WINDOW_SECONDS` |
| Per account (database mode) | 5 wrong passwords in a row → locked 15 minutes | `SIGNER_LOGIN_MAX_FAILURES`, `SIGNER_LOGIN_LOCK_MINUTES` |

A locked account gets the same 401, after the same bcrypt work, as a wrong
password. Failed logins are logged as a fingerprint (`id#…`), never as the
typed text.

## Known limits

Found in the post-implementation review (2026-10-16); none allows access
without the server's secret key or a valid password.

| # | Limit | Impact | Planned fix |
|---|---|---|---|
| L1 | ~~A correctly signed token with no `exp`, no `iat`, or a very long lifetime is accepted.~~ | **Fixed in Phase 2.1:** `exp` and `iat` are required, `iat` may not be more than 30 s in the future, and `exp − iat` may not exceed the longest configured lifetime (access 15 min / guest 60 min). | Tested in `SecurityHttpTests`. |
| L2 | ~~Behind a TLS proxy all users share one sign-in limit.~~ | **Fixed in Phase 2.1:** a production server with `SIGNER_TLS_OFFLOADED=true` refuses to start unless `SIGNER_FORWARD_HEADERS_STRATEGY=native` (or `framework`), so the limit sees real client addresses. Tomcat trusts `X-Forwarded-For` only from private/loopback proxies. | Tested in `ProductionSafetyValidatorTests`. |
| L3 | **Lockout can be triggered on purpose**: 5 wrong passwords every 15 minutes keep a known username locked, well under the address limit. Usernames are predictable (email local part). | Denial of service against one user. | Lock per account *and* address, add progressive delay, an operator unlock, and a notice to the user. |
| ~~L4~~ | ~~Logout ends the refresh token only; the **access token stays valid until it expires** (≤ 15 min). No deny-list.~~ | **Fixed in Phase D1:** the short-lived `jti` deny-list this row called for. Signing out now denies the access token presented with it until that token's own `exp`, so a sign-out ends the session rather than only its ability to be renewed. One device signing out still leaves the account's other sessions alone — that distinction is why a logout could not reuse the mechanism behind risk D7. | Tested in `LogoutDeniesAccessTokenHttpTests`, `AccessTokenDenyListTests`, `DbAccessTokenDenyListLiveTests` and `DeniedTokenMigrationLiveTests`; written up in Phase D1 §4. |
| L5 | Refresh sessions are **sliding**: each refresh extends the sign-in by 12 h, with no absolute maximum. | A session can be kept alive indefinitely. | Cap a family's total age (e.g. 7 days). |
| L6 | Two simultaneous refreshes of the same token: one succeeds, the other is treated as reuse and **ends the sign-in**. | Fail-safe, but a client that refreshes in parallel logs itself out. The desktop refreshes single-flight. | Keep; document for the website (Phase 6). |
| L7 | The address limit is **in memory, per instance**; the address map has no hard cap when more than 10 000 addresses are active at once; each IPv6 address counts separately. | Memory growth under a large distributed flood; limit does not survive restarts. | Hard cap with eviction; IPv6 /64 grouping; shared store if several instances run. |
| L8 | **File mode has no account lockout** (only the address limit), and the production validator does not require database mode. | Weaker brute-force protection for file-mode servers. | Require database mode in production, or add lockout to file mode. |
| L9 | Pages behind the emailed link (`/invitations/{token}`, `/sign/{token}`) carry the invitation token in the URL and send **no `Referrer-Policy`** header. | The token can leak through the `Referer` header to any external resource the page loads. | `Referrer-Policy: no-referrer` (and a CSP) on those pages. |
| L10 | A failed sign-in for an existing account also writes two counter updates, which an unknown account does not. | A few milliseconds of timing difference beside ~250 ms of bcrypt; enumeration would need many samples. | Accept, or move counting off the request path. |
## Tests

`SecurityHttpTests`, `LoginRateLimitHttpTests`, `LoginRateLimitFilterTests`,
`InMemoryRefreshTokenStoreTests`, `DbUserAccountsTests`,
`AuthenticationPersistenceLiveTests` and `RefreshTokenMigrationLiveTests`
(the last two need PostgreSQL).

Added with the L4 fix: `AccessTokenDenyListTests`,
`LogoutDeniesAccessTokenHttpTests`, and `DbAccessTokenDenyListLiveTests` plus
`DeniedTokenMigrationLiveTests` (both need PostgreSQL).
