# Security Phase 1 — identity foundation

Status: implemented. Scope: the account data model and password handling only.
Authentication flow, Spring Security filter chain, authorization, partner
credentials and the desktop session are later phases and are unchanged here.

## What changed

| Area | Before | After |
|---|---|---|
| Schema | `users` had no role, verification or lockout fields | `V5__account_identity_foundation.sql` adds `role` (USER/ADMIN, default USER), `email_verified_at`, `password_changed_at`, `failed_login_count`, `locked_until`. Additive only. |
| Usernames | unique, case-sensitive (`alice` and `Alice` could coexist) | also unique ignoring case (`uq_users_username_ci`). V5 stops with a clear message, changing nothing, if case-only duplicates already exist. |
| Status | free strings in five places | `AccountStatus` enum, same stored values |
| Hashing | `BCrypt.hashpw` / `checkpw` in five places, cost 10 for registrations | one `PasswordHashing` class, Spring Security `BCryptPasswordEncoder`, cost 12 |
| Old hashes | — | still verify; upgraded to cost 12 on the next successful login |
| Password length | 8–200 characters; over 72 bytes caused a server error | 8 characters to 72 UTF-8 bytes, rejected with a clear 400 |
| Unknown / disabled / invited account at login | answered instantly (timing leak) | same bcrypt work as a wrong password |
| Registration via invitation | set password, status | also sets `email_verified_at` and `password_changed_at` |

`failed_login_count` and `locked_until` are stored but not yet enforced —
lockout and rate limiting belong to Phase 2. `ADMIN` is never granted by code.

## Upgrading an existing database

Flyway applies V5 at the next server start (database mode only,
`SIGNER_DB_ENABLED=true`). If it reports case-only duplicate usernames,
rename one of each pair, then restart.

## Tests

- `PasswordHashingTests`, `DbUserAccountsTests` — unit tests.
- `AccountIdentityMigrationLiveTests` — V5 on a fresh database and on a V4
  database with accounts (needs PostgreSQL; uses its own throwaway database
  `signflow_v5_migration_test`).
- `PostgresWorkflowStoreLiveTests` — registration and seeding assertions on
  the new fields.
