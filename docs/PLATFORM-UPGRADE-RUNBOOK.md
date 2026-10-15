# Platform Upgrade — PostgreSQL, Invitations, Email (Runbook)

Companion to [ARCHITECTURE-ASSESSMENT.md](ARCHITECTURE-ASSESSMENT.md). Covers
what the platform upgrade added and how to run everything.

## What was added

| Area | Before | Now |
|---|---|---|
| Workflow persistence | `state.json` per workflow (files) | Same default **plus optional PostgreSQL** via JPA (`workflows`, `workflow_signers`, `document_versions`, `audit_events`); PDF bytes stay on disk in the identical layout |
| Users | YAML config map (`alice`, `bob`, `charlie`) | Same default **plus DB accounts** (bcrypt, email or username login, ACTIVE/DISABLED/PENDING_INVITE); dev seed migrates the config users on first DB startup |
| Invitations | none | Email invitations per signer slot: 256-bit token, **only the SHA-256 hash stored**, expiry, revoke, resend (old token invalidated), single-use acceptance bound to the invited email |
| Registration | none | `POST /api/auth/register` — invitation-bound; the email comes from the invitation record, never the client; placeholder accounts (`PENDING_INVITE`, no password) are activated transactionally |
| Email | none | `EmailService` abstraction: `console` (default — invitation links printed to the server log) and `smtp` (via `spring.mail.*` from the environment) |

Nothing changed for the existing default mode: `run-server.cmd` without
`SIGNER_DB_ENABLED` behaves exactly as before (file store + YAML accounts).

## Configuration (all environment-based)

| Variable | Meaning | Default |
|---|---|---|
| `SIGNER_DB_ENABLED` | `true` = PostgreSQL mode | `false` |
| `SIGNER_DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/signer` |
| `SIGNER_DB_USERNAME` / `SIGNER_DB_PASSWORD` | DB credentials | `postgres` / empty |
| `SIGNER_INVITATION_EXPIRATION` | Invitation TTL (ISO-8601 duration) | `PT48H` |
| `SIGNER_EMAIL_MODE` | `console` or `smtp` | `console` |
| `SIGNER_EMAIL_FROM` | From address (SMTP mode) | `signflow@localhost` |

SMTP relay itself is configured through standard `spring.mail.*` properties
(`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD`) — never committed.

## How to run PostgreSQL

PostgreSQL 16 is already installed and listening on 5432. Create the app
database once:

```sql
CREATE DATABASE signer;
```

(The test suite creates and drops `signer_test` by itself.)

## How to run the server (PostgreSQL mode)

```cmd
set SIGNER_DB_ENABLED=true
set SIGNER_DB_URL=jdbc:postgresql://localhost:5432/signer
set SIGNER_DB_USERNAME=postgres
set SIGNER_DB_PASSWORD=...
run-server.cmd
```

Flyway creates the schema on first start; the dev seeder inserts
alice/bob/charlie with bcrypt passwords (dev flag only).

## How to test email invitations locally

1. Start the server in DB mode (`SIGNER_EMAIL_MODE=console`).
2. Sign in as alice in the desktop (Remote workflow) and create a workflow
   typing an EMAIL as a signer (`bob@example.com` instead of `bob`).
3. Watch the server console — the "email" is printed there with the
   invitation deep link (`signflow://invitation/<token>`).
4. The invited signer registers through `POST /api/auth/register` with that
   token (UI integration below is pending), or logs in if their account is
   already ACTIVE and their email matches.

### Clicking the link instead of pasting it

A **packaged install** (built by `package.sh`, run as `signflow.packaged=true`)
registers `signflow://` for the current user itself on first launch —
`ProtocolRegistration` in `SignerFxApplication.init()` writes
`HKCU\Software\Classes\signflow` pointing at its own running executable
(`ProcessHandle.current()`, which jpackage makes the real native launcher
path). It re-registers whenever that path changes (reinstall, update,
relocation), so there is no install-time step and nothing a coworker runs
by hand. Undo with `unregister-signflow-protocol.cmd` if needed.

**Running from source** (`mvnw javafx:run`, `run-app.cmd`) skips
self-registration — use `register-signflow-protocol.cmd` instead to point
the same registry key at this checkout's jar for local deep-link testing;
re-run it after rebuilding to a new jar location.

Either way, clicking an emailed `signflow://invitation/<token>` link opens
the desktop app with the invitation dialog pre-filled. If the app is
already running, the second launch hands the token to the running instance
(local activation port 61234) and the dialog opens on top of the current
screen.

## How to test Alice → Bob → Charlie signing

Exactly as before (mock token PIN 1234, stable fingerprints) — the workflow
engine, certificate binding, turn claims, revision chain and idempotent
submissions are unchanged; only the persistence layer moved. The live E2E
test `RemoteWorkflowLiveE2ETests` still exercises it over real HTTP, and
`PostgresWorkflowStoreLiveTests.aggregateSurvivesRestartInPostgres` proves
the aggregate survives a server restart in PostgreSQL (fresh store instance,
turn claim, revision chain, append-only refusal intact).

## Invitation acceptance UI (brief §43)

The workflow-selection screen has an **Accept invitation…** button. The
signer pastes the emailed link, reviews document / inviter / email / expiry,
then either creates their signer account (password + display name →
`POST /api/auth/register`; the email comes from the invitation record) or
signs in and accepts (email-match enforced server-side). On success the
remote-workflow dialog opens pre-authenticated, straight on My Signing
Tasks.

## Signature placement persistence (brief §33)

The signing step takes optional placement input (page, x, y in PDF points,
origin bottom-left; a default 150×50 pt box is used). It is stamped into the
embedded workflow manifest at signing time, the server reads it back out of
each accepted revision (`SignaturePlacementExtractor`) and stores it on the
`workflow_signers` row (`signature_page/x/y/width/height`) with an audit
event (`SIGNATURE_PLACEMENT_RECORDED`). Placement is metadata only — it can
never reject a cryptographically valid submission.

## File→DB import

On the first database-mode startup, `FileStoreImporter` copies every
file-mode workflow into PostgreSQL (creating PENDING_INVITE placeholder
accounts for usernames the users table lacks). Idempotent — already-present
ids are skipped — and the legacy state.json files are left untouched as the
read-only source. PDF bytes are shared, not copied (both stores use the
same revision layout).

## Invitation expiry sweep

`InvitationExpirySweeper` (database mode, every 15 minutes) flips overdue
PENDING invitations to EXPIRED and writes `INVITATION_EXPIRED` audit events,
so expiration is active and visible instead of lazy-on-presentation.

## Tests

- `NavigationHistoryTests` — Back-navigation stack (5).
- `PostgresWorkflowStoreLiveTests` — live-PostgreSQL: dev seed accounts,
  aggregate restart round-trip incl. relational shape + append-only blobs,
  email-invitation → registration → single-use acceptance (3; skipped when
  no PostgreSQL is reachable). Self-provisions the `signer_test` database.

Full suite: **166/166 green**.
