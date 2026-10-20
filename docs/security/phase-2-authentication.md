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

- Logout ends the refresh token; the current access token stays valid until
  it expires (at most 15 minutes). No deny-list.
- The address limit is per server instance and in memory. Behind a proxy,
  enable `server.forward-headers-strategy=native` so the real client
  address is used; otherwise every client shares the proxy's address.
- Lockout can be triggered on purpose by someone who knows a username (it
  blocks that user for 15 minutes). The address limit slows this down; an
  operator unlock action is not built yet.
- File mode has no account lockout (only the address limit).
- The sequence diagrams in `docs/architecture` were drawn before this phase and still show the old `JwtAuthFilter`.

## Tests

`SecurityHttpTests`, `LoginRateLimitHttpTests`, `LoginRateLimitFilterTests`,
`InMemoryRefreshTokenStoreTests`, `DbUserAccountsTests`,
`AuthenticationPersistenceLiveTests` and `RefreshTokenMigrationLiveTests`
(the last two need PostgreSQL).
