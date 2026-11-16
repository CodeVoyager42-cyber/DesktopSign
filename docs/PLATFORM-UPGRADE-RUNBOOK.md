# Platform Upgrade — PostgreSQL and Email (Runbook)

Companion to [ARCHITECTURE-ASSESSMENT.md](ARCHITECTURE-ASSESSMENT.md). Covers
what the platform upgrade added and how to run everything.

> **The invitation workflow described by earlier versions of this runbook has
> been removed.** Nobody invites anybody: no e-mail invitations, no guest
> accounts, no 6-digit guest codes, no `/sign/{token}` page, no
> `POST /api/auth/register`. The sections that explained how to exercise
> them are gone from this document rather than left to be followed.
>
> **The only signing flow is:** sign in on the website → upload a PDF →
> click **Sign** → the browser offers *Open SignFlow* → the desktop opens
> through `signflow://launch/?v=1&c=<code>` → sign with the smart card.
> Every signer slot must name an account that already exists; a slot that
> looks like an e-mail address is refused.
>
> E-mail itself is RETAINED and still configured here: the account messages
> (confirm an address, reset a password) use it.

## What was added

| Area | Before | Now |
|---|---|---|
| Workflow persistence | `state.json` per workflow (files) | Same default **plus optional PostgreSQL** via JPA (`workflows`, `workflow_signers`, `document_versions`, `audit_events`); PDF bytes stay on disk in the identical layout |
| Users | YAML config map (`alice`, `bob`, `charlie`) | Same default **plus DB accounts** (bcrypt, email or username login, ACTIVE/DISABLED/PENDING_INVITE); dev seed migrates the config users on first DB startup. `PENDING_INVITE` survives only as the status of placeholder rows that were created before the removal |
| Invitations | none | **Removed.** They existed and are gone; the `invitations` table is kept read-only pending export, and its audit events are retained for ever |
| Registration | none | The website's own sign-up, `POST /web/api/auth/register`. The invitation-bound `POST /api/auth/register` is gone |
| Email | none | `EmailService` abstraction: `console` (default — links printed to the server log) and `smtp` (via `spring.mail.*` from the environment). Used by the ACCOUNT messages |

Nothing changed for the existing default mode: `run-server.cmd` without
`SIGNER_DB_ENABLED` behaves exactly as before (file store + YAML accounts).

## Configuration (all environment-based)

| Variable | Meaning | Default |
|---|---|---|
| `SIGNER_DB_ENABLED` | `true` = PostgreSQL mode | `false` |
| `SIGNER_DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/signer` |
| `SIGNER_DB_USERNAME` / `SIGNER_DB_PASSWORD` | DB credentials | `postgres` / empty |
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

## How to test the website → desktop hand-off

1. Start the server (PostgreSQL mode, above) and open the website.
2. Sign in, go to **Send a document**, upload a PDF and name it. You are the
   signer; no e-mail goes anywhere.
3. On the document screen press **Sign now**. The browser asks to open
   SignFlow; confirm it.
4. The desktop opens on that one signing task, with a session limited to it,
   and the smart card finishes the signature.

An old `signflow://invitation/<token>` link is refused with an explanation
rather than ignored: the desktop says invitation links no longer work and
points at the website.

## How to test Alice → Bob → Charlie signing

Exactly as before (mock token PIN 1234, stable fingerprints) — the workflow
engine, certificate binding, turn claims, revision chain and idempotent
submissions are unchanged; only the persistence layer moved. The live E2E
test `RemoteWorkflowLiveE2ETests` still exercises it over real HTTP, and
`PostgresWorkflowStoreLiveTests.aggregateSurvivesRestartInPostgres` proves
the aggregate survives a server restart in PostgreSQL (fresh store instance,
turn claim, revision chain, append-only refusal intact).

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

## Dropping the invitation data (V16 -> export -> V17)

The invitation workflow is gone from the code. Its table is removed in three
steps, because the third cannot be undone with `git revert`.

1. **V16** marks `invitations`, its 17 columns and
   `workflow_signers.invited_at` deprecated. Comments only, no DDL.
   Already applied.

2. **Export**, which is the step that makes the drop reversible:

   ```bash
   scripts/export-invitations.sh signer ./invitation-export
   ```

   It writes a CSV of every row, a manifest (row count, the CSV's SHA-256,
   the status breakdown, and the restore command with its column list) and a
   marker row in the database. **The CSV holds the e-mail address of
   everybody ever invited.** Move both files to wherever the audit trail is
   retained; `invitation-export/` is git-ignored so a stray copy cannot
   be committed by accident.

   The manifest also records the `audit_events` counts taken before the
   drop, which is how the drop is later shown not to have touched them.

3. **V17** drops the table, its four indexes and `invited_at`. It **refuses
   to run** against a non-empty table that has no export marker, so the export
   cannot be skipped: a server pointed at such a database fails to start
   rather than silently deleting rows. An empty table drops without a marker,
   which is what lets a fresh or test database migrate straight through.

`audit_events` is not touched by any of it. No migration in this removal
contains a `DELETE` or a `TRUNCATE`.

### Rolling V17 back

```bash
psql -U postgres -h localhost -d signer -f scripts/db/V17_down.sql
# then the restore line from the export manifest. It NAMES THE COLUMNS,
# because \copy maps them positionally and ignores the header, and an export
# taken before V2/V7 holds 11 columns, not the 17 the rollback recreates.
psql -U postgres -h localhost -d signer \
  -c "select setval('invitations_id_seq', (select max(id) from invitations))"
flyway repair   # or: delete from flyway_schema_history where version = '17'
```

Rehearsed end to end on a copy of a real database holding 18 rows: the drop
left `audit_events` at 257 rows with all 39 `INVITATION_*` rows present, and
the rollback restored all 18 invitation rows byte for byte.

## Tests

- `PostgresWorkflowStoreLiveTests` — live-PostgreSQL: dev seed accounts and
  the aggregate restart round-trip, including the relational shape and the
  append-only blobs. Skipped when no PostgreSQL is reachable; self-provisions
  the `signer_test` database.

  The invitation → registration → single-use acceptance case it also carried
  went with the invitation workflow.

Server suite at the time of the removal: **462 tests, 1 pre-existing failure**
(`RemoteWorkflowLiveE2ETests` — a 500 from `claimTurn`, unrelated to this
removal and reproducible before it).
