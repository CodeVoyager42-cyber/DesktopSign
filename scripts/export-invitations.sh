#!/usr/bin/env bash
#
# Exports the `invitations` table before V17 drops it, and records that the
# export happened so V17 will agree to run.
#
# The invitation workflow was removed in scope A. Its table is kept read-only
# by V16 and dropped by V17; this script is the step in between, and it is the
# only thing that makes the drop reversible.
#
#   scripts/export-invitations.sh [database] [output-directory]
#
# Defaults: database `signer`, output directory `./invitation-export`.
#
# WHAT IT WRITES
#   invitations-<db>-<timestamp>.csv       every row, header included
#   invitations-<db>-<timestamp>.manifest  row count, SHA-256 of the CSV, the
#                                          audit-event baseline, and the exact
#                                          command to restore it
#
# WHERE IT MUST GO. The CSV contains the e-mail address of everybody who was
# ever invited: it is personal data and it is the reason this is an export
# rather than a plain DROP. Move it to wherever the audit trail is retained.
# Do NOT leave it in the working copy — `invitation-export/` is git-ignored so
# a stray copy cannot be committed by accident.
#
# WHAT IT DOES NOT TOUCH. Nothing in `audit_events`. The INVITATION_* rows
# there are historical evidence of invitations that really were sent and
# accepted, they are retained for ever, and the manifest records their counts
# so the drop can be shown not to have changed them.
set -euo pipefail

DB="${1:-signer}"
OUT="${2:-./invitation-export}"
PGBIN="${PGBIN:-/c/Program Files/PostgreSQL/16/bin}"
PSQL="$PGBIN/psql.exe"
[ -x "$PSQL" ] || PSQL="psql"

command -v "$PSQL" >/dev/null 2>&1 || [ -x "$PSQL" ] || {
  echo "psql not found. Set PGBIN to your PostgreSQL bin directory." >&2
  exit 1
}

PSQL_USER="${PGUSER:-postgres}"
PSQL_HOST="${PGHOST:-localhost}"
run() { "$PSQL" -U "$PSQL_USER" -h "$PSQL_HOST" -d "$DB" -v ON_ERROR_STOP=1 "$@"; }
val() { run -A -t -c "$1" | tr -d '\r'; }

if ! val "select 1" >/dev/null 2>&1; then
  echo "Cannot connect to database '$DB' as '$PSQL_USER' on '$PSQL_HOST'." >&2
  exit 1
fi

if [ "$(val "select count(*) from information_schema.tables
             where table_name = 'invitations'")" = "0" ]; then
  echo "Database '$DB' has no invitations table — nothing to export."
  exit 0
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"
CSV="$OUT/invitations-$DB-$STAMP.csv"
MAN="$OUT/invitations-$DB-$STAMP.manifest"
[ -e "$CSV" ] && { echo "Refusing to overwrite $CSV" >&2; exit 1; }

ROWS="$(val "select count(*) from invitations")"

# The column list as THIS database actually has it, which is not always the
# full post-V7 shape: a database still at V1 has the 11 original columns and
# none of email_status (V2) or the five guest_code_* (V7). \copy maps CSV
# columns POSITIONALLY and ignores the header names, so restoring an 11-column
# export into the 17-column table that V17_down.sql creates fails unless the
# restore names its columns. The manifest below therefore spells them out.
COLS="$(val "select string_agg(column_name, ', ' order by ordinal_position)
             from information_schema.columns
             where table_schema = current_schema() and table_name = 'invitations'")"
NCOLS="$(val "select count(*) from information_schema.columns
              where table_schema = current_schema() and table_name = 'invitations'")"

echo "Exporting $ROWS row(s), $NCOLS column(s), from $DB.invitations"

run -c "\\copy (select * from invitations order by id) to '$CSV' with csv header"

SUM="$(sha256sum "$CSV" | cut -d' ' -f1)"

{
  echo "invitations export"
  echo "  database      : $DB"
  echo "  taken (UTC)   : $STAMP"
  echo "  rows          : $ROWS"
  echo "  columns       : $NCOLS"
  echo "  csv           : $(basename "$CSV")"
  echo "  sha256        : $SUM"
  echo
  echo "columns, in CSV order"
  echo "  $COLS"
  echo
  echo "status breakdown"
  val "select '  ' || status || ' : ' || count(*) from invitations
       group by status order by status"
  echo
  echo "workflow_signers.invited_at non-null (dropped by V17)"
  echo "  $(val "select count(*) from workflow_signers where invited_at is not null")"
  echo
  echo "audit_events baseline - V17 MUST NOT CHANGE THESE"
  val "select '  ' || event_type || ' : ' || count(*) from audit_events
       where event_type like 'INVITATION%' or event_type like 'EMAIL%'
       group by event_type order by event_type"
  echo "  TOTAL audit_events : $(val "select count(*) from audit_events")"
  echo
  echo "to restore"
  echo "  psql -U $PSQL_USER -h $PSQL_HOST -d $DB -f scripts/db/V17_down.sql"
  echo "  # The column list is REQUIRED: \\copy is positional, and this export"
  echo "  # has $NCOLS column(s) while V17_down.sql recreates the full 17."
  echo "  psql -U $PSQL_USER -h $PSQL_HOST -d $DB \\"
  echo "    -c \"\\\\copy invitations ($COLS) from '$(basename "$CSV")' with csv header\""
  echo "  psql -U $PSQL_USER -h $PSQL_HOST -d $DB \\"
  echo "    -c \"select setval('invitations_id_seq', (select max(id) from invitations))\""
  echo "  then: flyway repair, so the V17 row stops being counted as applied"
} > "$MAN"

# The marker V17 looks for. Without it V17 refuses to drop a NON-EMPTY table,
# so the export cannot be skipped by accident. An empty table needs no export
# and drops without one, which is what keeps fresh and test databases working.
run -c "create table if not exists invitations_exported (
            taken_at   timestamptz not null default now(),
            row_count  bigint      not null,
            csv_name   text        not null,
            csv_sha256 char(64)    not null
        )"
run -c "insert into invitations_exported (row_count, csv_name, csv_sha256)
        values ($ROWS, '$(basename "$CSV")', '$SUM')"

echo
echo "CSV      : $CSV"
echo "Manifest : $MAN"
echo "SHA-256  : $SUM"
echo
echo "Move both files to the audit retention store, then V17 may run."
