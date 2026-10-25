#!/usr/bin/env bash
#
# End-to-end check of the WEBSITE's own routes against a running server.
#
# Every route the website's screens call, in the order a person would cause
# it: register, confirm the address from the console mailbox, sign in, read a
# CSRF token, send a document as multipart, read it back, read its audit
# trail, read the invitations, resend one, prove CSRF and the session cookie
# are enforced, cancel, and read both dashboard lists.
#
# It exists because every one of those routes was green in the test suite and
# three defects still surfaced in the first five minutes against a real
# server (docs/security/phase-e-workflows.md section 7). What it does NOT
# check is that anything renders: no React runs here.
#
# Usage:
#   scripts/live-check.sh
#       registers a throwaway account and confirms it from the server log
#   LIVE_EMAIL=you@example.com LIVE_PASS=secret scripts/live-check.sh
#       reuses an account that is already confirmed — needed once five
#       sign-ups in an hour from this address have been used up (risk D1),
#       and the only way to run it when the server's log is not on disk
#   BASE=http://localhost:9000 SERVER_LOG=path/to.log scripts/live-check.sh
#       a server on another port, or a log kept somewhere else
#
# Requires: a running server (run-server.cmd, or the java command it prints)
# with its output redirected to SERVER_LOG when registering, because the
# console mailbox is where the verification link is printed in dev.
set -uo pipefail
cd "$(dirname "$0")/.."   # run from the project root

BASE="${BASE:-http://localhost:8080}"
SERVER_LOG="${SERVER_LOG:-target/server-live.log}"
PDF="${PDF:-postman/fixtures/original.pdf}"

# Registering is capped at 5 per address per hour (risk D1), which a few runs
# will reach. LIVE_EMAIL/LIVE_PASS reuse a confirmed account and skip it.
EMAIL="${LIVE_EMAIL:-send-check-$RANDOM@example.com}"
PASS="${LIVE_PASS:-correct-horse-staple-9}"
EXISTING="${LIVE_EMAIL:+yes}"

# Scratch files go in target/, which is ignored and disposable.
WORK=target/live-check
mkdir -p "$WORK"
H="$WORK/headers.txt"
B="$WORK/body.json"
REQ="$WORK/request.json"

# Paths handed to curl stay RELATIVE to the project root, deliberately: the
# curl on this machine is a mingw64 build that cannot open a POSIX path like
# /c/Users/... for an upload, and -s hides the reason (curl: (26)) so it
# surfaces only as a bare HTTP 000.
code() { curl -sS -o /dev/null -w "%{http_code}" "$@"; }

cookie() { # name, header file
  grep -i "^set-cookie: $1=" "$2" 2>/dev/null | tail -1 \
    | sed "s/^[Ss]et-[Cc]ookie: $1=\([^;]*\).*/\1/"
}

session_cookie() { # header file -> "name=value"
  # By elimination rather than by name: the production profile calls it
  # __Host-SF_SESSION and the dev profile SF_SESSION_DEV (Secure off, so
  # plain http on localhost works). Hard-coding either is wrong on the other.
  #
  # The cookie is carried by an explicit header below rather than by curl's
  # jar, because curl will not send a Secure cookie over http even to
  # localhost — and relaxing the server's cookie settings to suit the client
  # would test a configuration nobody ships.
  grep -i "^set-cookie:" "$1" 2>/dev/null | grep -iv "XSRF-TOKEN" | tail -1 \
    | sed 's/^[Ss]et-[Cc]ookie: \([^;]*\).*/\1/'
}

field() { grep -o "\"$1\":\"[^\"]*\"" "$2" 2>/dev/null | head -1 | cut -d'"' -f4; }
number() { grep -o "\"$1\":[0-9]*" "$2" 2>/dev/null | head -1 | cut -d: -f2; }
events() { grep -o '"eventType":"[A-Z_]*"' "$1" 2>/dev/null | cut -d'"' -f4 | tr '\n' ' '; }
count_of() { grep -o '"workflowId"' "$1" 2>/dev/null | wc -l; }

if ! curl -sf -m 5 "$BASE/api/health" > /dev/null 2>&1; then
  echo "No server is answering at $BASE — start run-server.cmd first." >&2
  exit 1
fi

echo "=============== 1. the pages exist and are public ==============="
for p in / /sign-in /sign-up /forgot-password /documents /document /send /open /account; do
  printf "  GET %-18s -> %s\n" "$p" "$(code "$BASE$p")"
done
printf "  GET %-18s -> %s (public, no auth)\n" "/api/health" "$(code "$BASE/api/health")"

echo
echo "=============== 2. a session, and a CSRF token ==============="
curl -sS -D "$H" -o /dev/null "$BASE/web/api/csrf"
XSRF=$(cookie "XSRF-TOKEN" "$H")
SESS=$(session_cookie "$H")
echo "  XSRF-TOKEN      : ${XSRF:0:10}…"
echo "  session cookie  : ${SESS%%=*}"
JAR="$SESS; XSRF-TOKEN=$XSRF"

if [ -n "$EXISTING" ]; then
  echo
  echo "=============== 3-4. account ==============="
  echo "  reusing an already confirmed account: $EMAIL"
else
  echo
  echo "=============== 3. register $EMAIL ==============="
  curl -sS -D "$H" -o "$B" -X POST "$BASE/web/api/auth/register" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"displayName\":\"Live Check\"}" \
    -w "  POST /web/api/auth/register -> %{http_code}\n"
  echo "  (202 whether or not the address is new — no account enumeration;"
  echo "   429 once five in an hour from this address are used up, risk D1)"

  if [ ! -r "$SERVER_LOG" ]; then
    echo "  !! $SERVER_LOG is not readable, so the console mailbox cannot be" >&2
    echo "     read. Re-run with LIVE_EMAIL/LIVE_PASS of a confirmed account." >&2
    exit 1
  fi
  TOKEN=$(grep -o "verify-email?token=[A-Za-z0-9_-]*" "$SERVER_LOG" | tail -1 | cut -d= -f2)
  echo "  verification token: ${TOKEN:0:12}… (${#TOKEN} chars, from the log)"

  echo
  echo "=============== 4. confirm the address ==============="
  curl -sS -D "$H" -o "$B" -X POST "$BASE/web/api/auth/verify-email" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR" \
    -d "{\"token\":\"$TOKEN\"}" \
    -w "  POST /web/api/auth/verify-email -> %{http_code}\n"
fi

echo
echo "=============== 5. sign in (by e-mail) ==============="
curl -sS -D "$H" -o "$B" -X POST "$BASE/web/api/auth/login" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR" \
  -d "{\"username\":\"$EMAIL\",\"password\":\"$PASS\"}" \
  -w "  POST /web/api/auth/login -> %{http_code}\n"
# Both rotate at sign-in: the session against fixation, the CSRF token with it.
ROTATED=$(session_cookie "$H"); [ -n "$ROTATED" ] && SESS="$ROTATED"
NEWXSRF=$(cookie "XSRF-TOKEN" "$H"); [ -n "$NEWXSRF" ] && XSRF="$NEWXSRF"
JAR="$SESS; XSRF-TOKEN=$XSRF"
echo "  rotated session : $(echo "${SESS#*=}" | cut -c1-10)…"
echo "  rotated XSRF    : ${XSRF:0:10}…"
echo "  profile         : $(cat "$B")"

SIGNER="live-check-signer-$RANDOM@example.com"
echo
echo "=============== 6. send a document (multipart) ==============="
cat > "$REQ" <<JSON
{"documentName":"live-check.pdf","signingPolicy":"SEQUENTIAL","signers":[{"order":1,"userId":"$SIGNER","certificateSha256":null}]}
JSON
echo "  signer slot 1   : $SIGNER (an address, so it is invited)"
# The request part is typed application/json: a plain string part arrives as
# text/plain, which Spring will not bind to @RequestPart.
curl -sS -D "$H" -o "$B" -X POST "$BASE/web/api/workflows" \
  -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR" \
  -F "request=@$REQ;type=application/json" \
  -F "file=@$PDF;type=application/pdf" \
  -w "  POST /web/api/workflows -> %{http_code}\n"
WF=$(field workflowId "$B")
echo "  workflow        : ${WF:-<none>}"
if [ -z "$WF" ]; then
  echo "  !! no workflow id — stopping here"
  head -c 400 "$B"; echo
  exit 1
fi

echo
echo "=============== 7. read it back, as /document does ==============="
curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/workflows/$WF" \
  -w "  GET /web/api/workflows/{id} -> %{http_code}\n"
echo "  status          : $(field status "$B")  (DRAFT until every slot binds"
echo "                    a certificate — the website never names one)"
curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/workflows/$WF/audit" \
  -w "  GET …/audit -> %{http_code}\n"
echo "  events          : $(events "$B")"
echo "                    (one WORKFLOW_CREATED, then CERTIFICATES_PENDING —"
echo "                     two WORKFLOW_CREATED means section 7.2 regressed)"
curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/workflows/$WF/invitations" \
  -w "  GET …/invitations -> %{http_code}\n"
INV=$(number invitationId "$B")
echo "  invitation      : id $INV, $(field status "$B"), e-mail $(field emailStatus "$B")"

curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/workflows/owned" \
  -w "  GET …/owned -> %{http_code}\n"
OWNED_BEFORE=$(count_of "$B")
echo "  owned before    : $OWNED_BEFORE"

echo
echo "=============== 8. resend that invitation ==============="
if [ -n "$INV" ]; then
  curl -sS -o "$B" -X POST "$BASE/web/api/invitations/$INV/resend" \
    -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR" \
    -w "  POST /web/api/invitations/$INV/resend -> %{http_code}\n"
  echo "  a new link went out; the previous one no longer works"
else
  echo "  !! no invitation id to resend"
fi

echo
echo "=============== 8b. the hand-off to the desktop (Phase F) ==============="
curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/app-info" \
  -w "  GET /web/api/app-info -> %{http_code}\n"
LINK=$(field downloadUrl "$B")
echo "  install link    : ${LINK:-none configured (null, as shipped)}"
# The sender is not a signer of this workflow, so the server refuses them a
# launch link: there is no signing task of theirs to open. 403 is the answer
# that proves the route is on the session chain and applying Phase B's rules.
printf "  POST …/launch-codes as the sender -> %s (403: not a signer)\n" \
  "$(code -X POST "$BASE/web/api/workflows/$WF/launch-codes" \
      -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR")"
curl -sS -o "$B" -X POST "$BASE/web/api/workflows/$WF/launch-codes" \
  -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR" > /dev/null
echo "  refusal         : $(field code "$B") — $(field message "$B")"

echo
echo "=============== 9. CSRF and the session are enforced ==============="
printf "  POST cancel with no CSRF header   -> %s (403 expected)\n" \
  "$(code -X POST "$BASE/web/api/workflows/$WF/cancel" -H "Cookie: $JAR")"
printf "  POST launch-codes, no CSRF header -> %s (403 expected)\n" \
  "$(code -X POST "$BASE/web/api/workflows/$WF/launch-codes" -H "Cookie: $JAR")"
printf "  GET  /web/api/auth/me with no cookie -> %s (401 expected)\n" \
  "$(code "$BASE/web/api/auth/me")"

echo
echo "=============== 10. cancel it ==============="
curl -sS -o "$B" -X POST "$BASE/web/api/workflows/$WF/cancel" \
  -H "X-XSRF-TOKEN: $XSRF" -H "Cookie: $JAR" \
  -w "  POST …/cancel -> %{http_code}\n"
echo "  status now      : $(field status "$B")"
curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/workflows/$WF/audit" \
  -w "  GET …/audit -> %{http_code}\n"
echo "  events          : $(events "$B")"

echo
echo "=============== 11. the dashboard's two lists ==============="
curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/workflows/owned" \
  -w "  GET …/owned -> %{http_code}\n"
OWNED_AFTER=$(count_of "$B")
echo "  owned after     : $OWNED_AFTER (same as before: a cancelled document"
echo "                    stays in its sender's list — section 7.1)"
if [ "$OWNED_BEFORE" -ne "$OWNED_AFTER" ]; then
  echo "  !! REGRESSED: cancelling dropped it from /owned" >&2
  exit 1
fi
curl -sS -o "$B" -H "Cookie: $JAR" "$BASE/web/api/workflows/participating" \
  -w "  GET …/participating -> %{http_code}\n"

echo
echo "done."
