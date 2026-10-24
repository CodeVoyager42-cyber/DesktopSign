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

| Event | Website sessions | Desktop refresh tokens | Bearer tokens already issued |
|---|---|---|---|
| Password **reset** (link) | **all** revoked | **all** revoked | **all** refused (D7) |
| Password **change** (signed in) | all **except the caller's own** | **all** revoked | **all** refused (D7) |
| **Sign out** (one device) | that browser's session | the presented family | **that one token** (L4) |

The last row is the one that has to be narrow: the other two end everything on
purpose, and a sign-out that did the same would sign someone out of their
laptop because they closed the app on their phone.

Sessions are **deleted** from `spring_session`, not flagged, so the cookies
that referenced them are dead on every server instance immediately. The
lookup is by `principal_name`, which V9 already indexes.

A reset ends every session because whoever asked for it may not be the person
who had the old password. A change keeps the browser doing the change signed
in, because signing someone out of the tab they are typing in is pointless
friction — but every *other* session goes.

### And the bearer tokens, which cannot be deleted — risk D7

A session is a row and a refresh token is a row, so revoking them is a
delete. An access token is a **signed JWT**: nothing is stored for it, so
there is nothing to delete, and it kept working for the rest of its life.

`UserAccountService.passwordChangedSince(username, iat)` closes that, and it
costs nothing extra: `AuthenticatedRequestFilter` already loads the account on
every request to see whether it can still sign in at all, and
`password_changed_at` has been written by `UserEntity.changePasswordHash`
since this phase began — both password paths go through it. **No migration,
and no new per-request work.**

| | |
|---|---|
| **Both token types** | the account access token (15 min) *and* the desktop task session from a launch code (**30 min**). The risk named only the first; stopping there would have left a window twice as long as the one being closed. |
| Not guest sessions | a guest has no account and no password. Its own invitation state already governs it. |
| **Not a rehash** | `DbUserAccounts.rehash` upgrades a bcrypt cost on sign-in and deliberately uses `setPasswordHash`, which does *not* stamp the column. Stamping it there would sign an account out of its other sessions every time it signed in. |
| Nothing retroactive | an account that has never changed its password has a null column and answers `false`, so deploying this signs nobody out. |
| **Not logout** | Signing out is the sibling gap (Phase 2 risk **L4**) and this fix cannot close it: a logout is not a password change and must not pretend to be one — stamping `password_changed_at` on logout would end every *other* device's tokens too, which is not what signing out of one means. It needed a deny-list of its own, below. |

**The one trade-off, and it is a real one.** A JWT's `iat` is whole seconds
while `password_changed_at` is microseconds, so a token issued *300 ms before*
a change and one issued *300 ms after* it can carry the same `iat`. They
cannot be told apart, and something has to give:

- compare strictly, and a sign-in in the same second as the change is refused
  its brand new token — breaking "reset, then sign in again", the very path
  this exists to serve, with no clean recovery, because the change has
  revoked the refresh tokens too;
- compare at the token's own resolution, and a token issued inside the
  change's own second survives its full life.

The second is chosen: it turns a 15-to-30-minute window into a sub-second
one, where the first would break the common flow outright. The window that
remains needs the attacker's token to have been issued in the same second as
the victim's change, which they cannot arrange.

This is also what the first version of the HTTP tests got wrong — written
without aligning to a second boundary, they passed or failed on where the
boundary fell, which is how the trade-off was noticed at all.

### And signing out of one device — Phase 2 risk L4, resolved

A password change ends **every** token of the account, which is right for a
password change and wrong for a sign-out: signing out of a phone must not sign
out the laptop. So the mechanism above could not be reused, and L4 needed what
Phase 2 said it needed — a short-lived `jti` deny-list, naming the one token
being ended.

`POST /api/auth/logout` now does two things. It revokes the refresh session,
as it always did, and it denies the access token **presented with it** until
that token's own `exp`. `AuthenticatedRequestFilter` checks the list before it
looks the account up at all: a token that has been signed out of is finished,
whatever the account behind it is doing.

| | |
|---|---|
| **Only what it is shown** | The server cannot delete a signed JWT, so it can only refuse one it has been told about. The jti comes from the bearer header on the logout request — so the desktop client now sends its access token with the logout instead of merely dropping it (`WorkflowApiClient.Session.close`). A client that posts the refresh token alone still gets the old behaviour, and `LogoutDeniesAccessTokenHttpTests` asserts exactly that rather than hiding it. |
| **A useless token still cannot block a logout** | `/api/auth/logout` is one of `SecurityConfig.PUBLIC` on purpose: a stale or malformed access token must never be the reason someone cannot sign out. The chain therefore does not resolve the bearer for it, and the controller reads the header **by hand** and validates it itself. Anything the decoder refuses leaves the logout a 204 with nothing denied — there is nothing a refused token could still do. |
| No cross-check between the two tokens | and none is needed. Denying a token requires presenting it, and whoever holds a token can already use it; ending it is the one thing they can do with it that nobody needs protecting from. |
| **The jti is stored as it is, not hashed** | the one place this differs from `refresh_tokens` and the V10 link tokens. Those hold hashes because the value *is* the credential — whoever reads the row could use it. A jti is an identifier inside a token signed with a secret the table does not contain, so a row grants nothing, and an id that can be read back is one an operator can match against a log line. |
| No column for the account | a row says *this one token is dead*, not who signed out and when from where. The sweep needs only the expiry and the per-request check needs only the id. |
| **It fails closed** | deliberately the opposite of the rate limits (§6), for a specific reason rather than taste: a revocation check that answers "not revoked" when it does not know is not a revocation check, and an attacker able to provoke database errors could re-enable every token that had been signed out of. It costs nothing here — the filter has already read the account from the same database, twice, before it asks this question — so a failure is left to propagate instead of being swallowed into a `false`. |
| A token with no `jti` is refused outright | every token this server issues carries one. One without it could never be denied, so accepting it would leave the deny-list one missing field away from being bypassable — the same reasoning as Phase 2.1 requiring `exp` and `iat`. |
| **Bounded by construction** | an entry lives until the token's own `exp` (≤ 15 min) and never longer, so the list is bounded by how many sign-outs happen inside one token lifetime — and every entry requires a validly signed, unexpired token to have been presented. There is deliberately **no cap**: evicting an entry un-revokes a token, so whoever wanted that could simply sign in repeatedly until the entry they cared about was pushed out. |

Two implementations, chosen by `SIGNER_DB_ENABLED` exactly as the refresh
store and the rate-limit store are: `denied_access_tokens` (V12) when there is
a database, so a logout handled by one instance reaches the others, and a map
on the heap when there is not. **Which is also the residual:** behind two
file-mode servers, a logout reaches one of them and the token still works on
the other until it expires. With a database it does not, and that is the
deployment the production validator already pushes towards.

### And the holder is told — risk D8

Both paths then e-mail the account: *your password was changed, at this time,
and this is what it means*. That notice is how someone normally finds out their
account has been taken over, so it matters more than the convenience of not
sending it.

| | |
|---|---|
| **No link, no token** | deliberately. A message saying something is wrong with your account is exactly what an attacker would dress up as a reset link, so this one gives the reader nothing to click and says so. |
| Two wordings | a reset says the link was enough and no old password was needed; a change says whoever did it knew the old password. Only one of those tells the holder their password itself had leaked. |
| **Best-effort** | the password has already changed and the sessions are already gone when it is sent. A mail server that is down must not undo any of that, so a failure is logged (its class, never the address) and swallowed. |
| **Not counted against the e-mail cap** | each notice requires a *successful* password change — a valid one-time token, or the current password — so it cannot be used to flood anyone; and a cap that suppressed exactly the "someone changed your password" message would defeat the point of having it. |
| Nothing is sent | when the account has no address on file, and when the attempt failed (a wrong current password must not alarm anyone). |

## 5. The unverified gate

`UserAccountService.maySign(username)` is the seam, default `true`.
`DbUserAccounts` implements it as "active **and** `email_verified_at` is not
null". File-mode accounts have no address to verify, so they always may.

An unverified account may sign in, see `/web/api/auth/me` with
`emailVerified: false`, list its workflows and read where a task stands. What
it may not do is take any step of signing. Every such action is gated, each
answering `403 EMAIL_NOT_VERIFIED`:

| Where | Gates |
|---|---|
| `RemoteWorkflowService.createWorkflow` | starting a signing request — in the service, so it holds for **every** caller path, including the website routes of Phase E |
| `AuthenticatedRequestFilter`, via `SigningActionPolicy` | the account-token requests: `POST …/claim`, **`GET …/document`**, `POST …/signatures`, `…/signers/{user}/certificate`, `…/launch-codes` |
| `LaunchCodeService` | issuing a launch code, exchanging one for a task session, and re-checking that session on **every** request (`taskSessionState` → `INVALID` → 401) |

`SigningActionPolicy` is an anchored path list in the style of
`TaskAccessPolicy`, pairing each path with its method. It is checked in the
filter rather than in the services because that is where the **kind** of
principal is known, which matters for the exception below.

The document download is the one **read** in it. It is there because it is
step one of signing rather than a way of looking around: handing the document
to an account that has not proved its address serves no purpose when it cannot
submit the result. `HEAD` counts as the download too, since Spring serves it
from the same handler. Listing workflows and reading a task, its revisions and
its audit trail stay open.

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

### Where the counts live — risks D3, C6 and D6

Every limit below counts through one seam, `RateLimitStore`:

```
RateLimitStore.retryAfterSeconds(bucket, max, window)
  ├── DbRateLimitStore        rate_limit_hits (V11) — when there is a database
  └── InMemoryRateLimitStore  a map on the heap     — when there is not
```

Deciding and counting are the same call, so a caller cannot ask whether it
may act and then forget to record that it did. The window is a genuine
sliding one — the count is of the rows inside it and `Retry-After` is measured
from the **oldest** row still in it — which is why the hit times are kept
rather than one counter per bucket.

Until now every limit was a map on the heap. That made each cap **per server
instance** and reset it on every restart: behind two instances a cap of 20 was
really 40, and a restart handed an attacker a fresh budget. The sign-up
throttle suffered most, its window being an hour. That was risk **D3** for the
identity limits and **C6** for the address limit; both are now closed wherever
a database is configured.

Three decisions hold the database implementation up:

| | |
|---|---|
| **Its own connection, always** | It never joins the caller's transaction. The per-account token-attempt cap is called inside a transaction that *rolls back on a failed attempt*, and a count that vanished with the rollback would mean failures — the attempts worth counting — were never counted. |
| **Serialised per bucket** | Counting and inserting are two statements, so without a lock concurrent callers would each read the same count and each be admitted, turning a cap of 20 into 20 *per concurrent request*. There is no bucket row to lock, so the bucket name is locked with `pg_advisory_xact_lock(hashtext(bucket))`, released when the transaction ends. |
| **It fails open** | An unreachable database allows the attempt, logged at WARN. A limiter is an availability guard, not an authentication gate: the gate is the account lockout, which is persistent and in that same database, and every endpoint behind these limits needs that database to do anything at all — so failing closed would answer `429` to requests that were going to fail anyway, turning an outage into a lockout of everyone. |

Buckets are namespaced by the limiter that owns them — `addr:`, `signup:`,
`mail:`, `attempt:` — so limiters sharing the store cannot spend each other's
budget. A bucket holds a client address or an e-mail address, **never** a
password, a token or a token hash: these rows outlive the request.

This is also the end of risk **D6**. The sliding window used to be written
twice, once in `LoginRateLimitFilter` and once in `IdentityRateLimiter`. It is
now written once per store, and both of those are thin callers: what is left
in `IdentityRateLimiter` is lower-casing an identity, namespacing it, and
declining to guess when there is nothing to key on.

### Sign-up alone — `SignUpThrottleFilter`

At most **5 new accounts per client address per hour**
(`SIGNER_WEB_SIGNUP_LIMIT`, `SIGNER_WEB_SIGNUP_WINDOW`), answering `429` with
`Retry-After`. It runs **before** the shared limiter on the `/web/**` chain, so
a sign-up flood is turned away without spending the budget the other account
routes share.

A third limit rather than a tightening of either existing one, because neither
bounds sign-up:

- the shared `|account` bucket allows 20 requests a **minute** per address
  across all six account routes — right for someone clicking a link twice,
  far too generous for creating accounts;
- the per-recipient e-mail cap is keyed by the **recipient**, so a script
  using a fresh address every time never comes near it.

Minutes are the wrong unit for sign-up, and making the shared bucket this
strict would punish someone retrying a verification link. A limit below 1, or
a non-positive window, fails startup rather than silently allowing everything.

**Why not a CAPTCHA.** One would mean a third-party service, an outbound call
from a server that deliberately makes none, and a page to host the widget —
and there is no frontend yet. The two are not alternatives in the long run: a
CAPTCHA belongs with the sign-up page (D2), on top of this.

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
otherwise the cap itself would be an oracle. The counts are now shared and
persistent where a database is configured (above), so these two caps mean what
they say across instances and restarts; the tokens are still short-lived and
single use, because a limit is never the only thing protecting them.

### Per account and address, and progressively — Phase 2 risk L3

`DbUserAccounts` locks for **15 min** after **5** wrong passwords in a row, as
it always has, counted across both sign-in chains — but the count that locks
is now per **(account, client address)** pair, not per account.

The old shape was the risk. Five wrong passwords from anywhere locked the
account for *everyone*, the holder included, and a username is usually an
e-mail local part, so keeping a known person locked out of their own account
cost five requests every fifteen minutes — comfortably under the per-address
rate limit of twenty a minute. Phase 2 recorded it as **L3** and listed what
it needed: lock per account *and* address, a progressive delay, an operator
unlock and a notice to the holder. **All four are now done**, which does not
mean the risk is closed — see the end of this section and §11.

#### A refused attempt is not a counted one

Worth stating, because it is easy to assume otherwise and one test did. The
delay is checked **before** the password: an attempt made while the pair is
refused is answered without the password being looked at, so it does **not**
increment the counter. The count therefore advances only on attempts that got
as far as a password check — five *counted* failures to reach the
`lock-minutes` lock, not five requests.

For an attacker this is the right way round: attempts made during a refusal
cost them the wait and buy them nothing. What it breaks is the intuition that
*n* requests produce *n* failures, which is how
`WebSessionHttpTests.accountLockoutIsSharedWithTheDesktopLogin` came to pass
for the wrong reason — it fired five attempts back to back, and the two after
the third were swallowed by that failure's 2-second refusal. It passed
because they were answered 401 *by the refusal* rather than by the lock it
meant to assert, and it failed on any run slow enough for the refusal to
expire first, leaving the count at three and the correct password accepted.
It now waits each delay out and reads the counter from `login_failures`
directly, where four failures from the two chains have to appear as one row.

#### Telling the holder — the notice

When the **account-wide** lock fires, the account's address gets a message:
your account is locked, it unlocks by itself at this time, if that was your
own typing you can reset your password instead, and if it was not you then
someone is guessing — they have not got in, and this message would say your
password had changed if they had.

| | |
|---|---|
| **Only the account-wide lock sends it, never a per-address one** | This is the whole design, and the rest is detail. A pair lock is what an attacker trips — on purpose, cheaply, from every address they have — so notifying on it would have turned the notice into a way to **mail-bomb the holder**, which is a worse denial of service than the lockout L3 is about. The account-wide lock costs 50 failures to reach and resets its count when it fires, so it is already dear to trigger even once. |
| **Capped anyway** | One per account per 6 hours, through the same `RateLimitStore` as every other limit (§6), in its own bucket so it cannot spend or be spent by the sign-in budget. This is the **opposite** choice from the password-change notice (risk D8), which is deliberately *not* capped — that one needs a successful password change, so only the holder can cause it, and suppressing it would hide the one message that matters most. This one a stranger can cause, so it must be capped. |
| Verified addresses only | An unverified address has not been shown to belong to the holder, and the sign-up confirmation is the only thing that should ever reach it. |
| **No link, and no address** | No link for the same reason as the D8 notice: a message saying something is wrong with your account is precisely what a phishing mail imitates. And it does not say where the attempts came from — the server knows, but it is usually a proxy or a shared NAT address, it may belong to an innocent third party, and there is nothing a recipient can do with it. |
| Best-effort, and last | The lock is already in place when it is sent, so a mail server that throws must not turn a refused sign-in into a server error. A failure is logged as a class name. |

#### Getting back in — the unlock

A lock that can hold an address for a day needs a way back that is not
waiting, and there are two.

**A successful password reset unlocks the account by itself.** An e-mailed
one-time token proves the holder's identity more strongly than a sign-in
attempt does, so `applyNewPassword` — the single funnel both the reset and the
signed-in change go through — clears the account lock and forgets every
address's. Most lockouts should therefore never reach an operator. The
distinction that keeps this from being a hole: only **confirming** a reset
clears anything. *Requesting* one needs no token and no secret, so if that
cleared the lock, anyone could clear it for anyone; a test asserts it does
not.

**And an operator can clear one directly**, with a command-line option rather
than a route:

```
java -jar signflow.jar \
    --spring.profiles.active=server \
    --spring.main.web-application-type=none \
    --signer.unlock-account=alice
```

| | |
|---|---|
| **Why not an admin route** | Because §12 says there are none, and that is worth keeping rather than quietly reversing for one operation. An endpoint needs an operator credential this deployment does not have: either a role system, which it also deliberately does not have, or a static shared token — a new high-value secret on the network, with no rotation and no attribution, whose holder could clear their own lock repeatedly and defeat the very control the unlock exists to relieve. |
| **Safer than the alternative it replaces** | which is support staff running SQL by hand against production. `DELETE FROM login_failures` is one forgotten `WHERE` away from unlocking everybody, and it leaves no record. |
| The bean does not exist without the option | `@ConditionalOnProperty`, so a normal server does not carry a start-up action that clears security state. Both halves of that are tested — present with the option, absent without it. |
| **It grants nothing** | An unlock removes refusals and only refusals: the next attempt still has to present the right password, the hash is untouched, and it does not count as a password change, so no token is invalidated by it. A test asserts the wrong password is still wrong afterwards. |
| An unknown name is an error, not a no-op | An operator who mistypes, or who passes the e-mail address instead of the username, is told so — rather than shown something that reads like success. |
| **Logged at WARN** | with the username and the counts, never an address. An administrative action that removes a security control should be visible in the log it leaves behind, and that log is the only record: `audit_events` is workflow-scoped and has nowhere to put an account-level action. |
| Idempotent | Unlocking an account that is not locked reports that it cleared nothing. |

#### The progressive delay

Every failure sets the pair's "not before", and the duration grows with how
many failures that pair has in a row:

| Consecutive failures from one address | Next attempt allowed after |
|---|---|
| 1–2 | immediately — people mistype their own password |
| 3 | 2 s |
| 4 | 8 s |
| 5 | `lock-minutes` — 15 min, the lock this always had |
| 6 | 30 min |
| 7 | 1 h |
| 8+ | doubling, capped at **24 h** |

| | |
|---|---|
| **A refusal, not a slow answer** | The obvious reading of "progressive delay" is to make the request itself take longer, and it would be a mistake: holding a servlet worker thread for seconds hands an attacker resource exhaustion for the price of opening connections, and a 30-second sleep across a 200-thread pool is a dead server. The answer comes back immediately and says the attempt was refused. |
| **The count survives the lock** | which is the part that actually closes something. Before, locking reset the count, so when the lock expired the same address got another five fast attempts — five guesses every fifteen minutes, *for ever*. Now the sixth failure costs half an hour and the seventh an hour, so an attacker who keeps coming back pays exponentially for guesses they are not getting. Only a successful sign-in from that address, or the sweep forgetting the idle pair, clears it. |
| **The holder pays nothing** | The delay belongs to a pair, and the holder's address has no failures on it. The first two failures being free is for them too: two tries at 250 ms of bcrypt is not a brute-force budget, and someone who mistypes twice and gets it right on the third try never meets any of this. |
| The threshold decides first | A deployment that sets `max-failures` to 3 gets its lock at 3, and the 2 s and 8 s steps only ever fill the room *below* the threshold. Checking them first was the first version of this and was wrong — such a deployment would never have reached its own lock, because the eighth second would have answered for it. |
| **The cap equals the retention** | 24 h both. The sweep forgets a pair a day after its last failure and forgetting it releases the lock, so a cap *longer* than the retention would mean the longest locks were quietly cut short by housekeeping. A test pins the relation so changing one cannot silently break the other. |

What the delay does **not** do is make the account-wide backstop dearer: ten
addresses in parallel still spend five failures each in about ten seconds,
because each address starts its own escalation from zero. Making that
expensive without handing an attacker a way to delay the holder is the part
with no clean answer here — see the residual below.

| | |
|---|---|
| **Both scopes are counted** | A failure increments the pair row (V13 `login_failures`) and the account row (`users.failed_login_count`, V5, untouched). An attempt is refused if *either* is locked, and both are checked before any password is. |
| **5 per pair, 50 per account** | The pair keeps the number the account used to have, so one attacker from one address gets exactly the five tries they always got. The account-wide threshold is ten times that (`SIGNER_LOGIN_ACCOUNT_MAX_FAILURES`), and the server **refuses to start** if it is set below the per-address figure — otherwise the account-wide lock would fire first, the per-address lock could never fire, and the deployment would silently be back to locking the holder out. |
| **Why keep the account-wide lock at all** | Because without it the per-address lock *is* the only limit, and rotating addresses — cheap with IPv6 — would make guessing unbounded. Keeping it is what stops the fix from trading a denial of service for a brute-force hole. |
| The remote address, never a header | `X-Forwarded-For` is ignored, exactly as the rate limiter ignores it: a client that can choose its own lockout key has no lockout. A server behind a proxy must rewrite the remote address (`SIGNER_FORWARD_HEADERS_STRATEGY=native`), which the production validator already requires when TLS is offloaded. |
| **One pair, both routes** | `/api/auth/login` and `/web/api/auth/login` check the same passwords against the same accounts, so they count against the same pair rather than getting five attempts each — the same reasoning that makes the rate-limit filter a shared singleton. |
| A refusal is not a failure | Being turned away by a lock is not counted at either scope, so a lock cannot extend itself, and an attacker hammering a locked pair cannot drive the account-wide count to its threshold. |
| **Success forgets the pair** | and only that pair. Clearing every pair of the account on a successful sign-in would hand an attacker five fresh attempts every time the holder signed in. |
| No address is stored for an unknown account | An unknown or disabled account gets the same answer after the same bcrypt work as a wrong password, and no row: creating one would be a way to find out that it has none. |

**What it costs, stated plainly.** This does not eliminate the denial of
service, it prices it up: locking a holder out takes 50 failures from at
least 10 addresses instead of 5 from one. With IPv6 that is not expensive,
and the progressive delay does not change it — a rotating attacker meets a
fresh escalation on every new address. What the delay does buy is the thing
that was genuinely unbounded: guessing from one address, indefinitely, five
attempts at a time. All four of L3's parts are now
built, and the exposure is still there: **finishing a list is not closing a
risk.** The unlock shortens how long a successful attack hurts and the notice
tells the victim it is happening — both worth having, both recovery rather
than prevention. What would actually stop it is something this phase has not
built: a reputation or proof-of-work input to the sign-in path, or a CAPTCHA,
which belongs with the frontend (D2). L3 stays open at Low for that reason,
with its four parts marked done.

**And it stores an address beside an account**, which nothing here did
before: `rate_limit_hits` holds addresses but is not account-linked, and the
password-change notice (§4) deliberately has no address to report. A
`login_failures` row is that linkage. It is kept because the lock cannot work
without it, bounded by a sweep that forgets a pair a day after its last
failure (§8), never indexed by address alone, and cascaded away with the
account. Addresses already reach the log — the rate limiter has always logged
them, and a pair lock logs the address beside the account at WARN, which is
the one line an operator needs during an attack.

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

## 7a. The response headers — Phase 2 risk L9 and Phase C risk C7, resolved

Both filter chains now send two headers on **every** response.

**`Referrer-Policy: no-referrer`** — risk **L9**. The pages behind an e-mailed
link carry a one-time token *in the URL* (`/invitations/{token}`), and the
default browser behaviour is to put the current URL in the `Referer` of
anything the page fetches and of any link the reader follows. That page links
out to a download site, so the leak was not hypothetical. Nothing here needs
referrers, so the policy is the strictest one everywhere rather than something
narrower on the two routes someone remembered.

**`Content-Security-Policy`** — Phase C risk **C7**, carried into this phase
as **D10**:

```
default-src 'none'; style-src 'unsafe-inline'; form-action 'none';
base-uri 'none'; frame-ancestors 'none'
```

| | |
|---|---|
| **No script, at all** | `default-src 'none'` with nothing relaxing it for script. The server-rendered pages contain none, so none is permitted, and injected script would not run even if some escaping were wrong. `SignPageRendererTests` asserts the pages stay script-free, so adding one fails a test instead of being silently blocked in a browser. |
| **`style-src 'unsafe-inline'` is the one concession** | and it is honest rather than ideal. `SignPageRenderer` puts its stylesheet in an inline `<style>` block and uses two inline `style=` attributes, and a nonce does **not** cover style attributes — only `'unsafe-inline'` does. What it permits is injected CSS: the page can be made to look wrong, nothing can execute. |
| One policy, both chains | from one place (`SecurityHeaders`). Two would drift, and the one that drifted would be the one nobody was looking at. The website chain is asserted separately in `WebAccountHttpTests` for exactly that reason. |
| **D2 will have to widen it** | A real frontend needs at least `script-src 'self'`, `style-src 'self'`, `img-src 'self'` and `connect-src 'self'`. Starting strict and relaxing deliberately is the right direction; starting permissive and hoping to tighten later is how a CSP ends up meaning nothing. |

**What was already there and is kept:** neither chain configured `headers()`
before, so Spring Security's defaults were in force — `X-Content-Type-Options:
nosniff`, `X-Frame-Options: DENY`, and `Cache-Control: no-cache, no-store,
max-age=0, must-revalidate`, the last of which matters for a URL with a token
in it. The tests assert those too, so switching them off cannot pass
unnoticed.

**Where L9's page is actually tested.** `SignPageController` is conditional on
a database, so in file mode `/invitations/{token}` answers without rendering
the page. That does not weaken the header test, because the header is a
property of the filter chain rather than the handler — it is asserted on that
very path, on a JSON route, and on an unauthenticated 401 (which answers from
`JsonSecurityErrors`, not a controller, so it is worth checking it has not
bypassed the header writer). The rendered page is covered separately, without
HTTP, by `SignPageRendererTests`.

## 7b. Serving the website — same origin, decided

The frontend is served by **this application, from its own origin**. That is a
security decision before it is a deployment one, and the reason is the cookie:
it is named with the `__Host-` prefix and sent `Secure` with `SameSite=Lax`
(Phase C), and that combination only works when the pages using it come from
the same host. A frontend on a second origin — a separate dev server, a CDN,
another hostname — would have forced `SameSite=None`, dropped the `__Host-`
prefix, and required CORS with credentials: three weakenings of a posture
that is already built and tested, in exchange for nothing the deployment
needs.

So **there is no CORS configuration in this application**, and that is the
decision rather than an omission. A test sends an `Origin` header and asserts
no `Access-Control-*` header comes back, which is where that change would
have to announce itself.

| | |
|---|---|
| Static files | `src/main/resources/static`, served by Spring Boot's own resource handling. Built assets belong in `static/assets/`. |
| **Routes are listed, not wildcarded** | `/`, `/index.html`, `/favicon.ico`, `/assets/**` and the two e-mail pages are named individually in `SecurityConfig.PUBLIC`. Both chains end in `denyAll()`, so a path that answers at all is one somebody listed — and `FrontendServingHttpTests` is the record of which. |
| **No catch-all SPA fallback** | `FrontendRoutingConfiguration` forwards `/verify-email` and `/reset-password` to the entry point by name. A `/**` forward is the usual shortcut and a trap: it answers every mistyped API path with HTML and a 200, which a client cannot tell from success. A new page costs a line here, and 404s stay honest. |
| The two e-mail links finally lead somewhere | They have always carried these URLs and nothing served them (§9 said so). Both now open the page that uses them. |
| **The website itself** | `static/index.html` plus `assets/app.js`, `assets/api.js` and `assets/app.css`: sign up, confirm an address, sign in, ask for a reset link, set a new password from one, change a password, resend a confirmation, sign out. That is the whole account API and nothing more — there are no workflow routes under `/web/api` to call yet (Phase E). The visual side of it, the tokens and the planned Phase E/F screens are in **`docs/frontend-design.md`**. |
| **Inter is self-hosted, and absent** | `font-src 'self'` permits no font service, which is the point — so the stylesheet points `@font-face` at `/assets/fonts/`. The two `.woff2` files are a licensed binary and are **not in the repository**; the stack falls through to the platform UI font until they are added, so nothing is broken without them. |
| **Plain ES modules, no build step** | No Node toolchain, no `node_modules`, nothing to install before the server can serve its own website. A module from `/assets` is what `script-src 'self'` permits, and the same reasoning that kept Redis out of the rate limits (§12) applies here: a second piece of required tooling for this much is a poor trade. A bundler can replace this later without the server noticing. |
| **No inline script, and no inline handler** | Every listener is `addEventListener`; the document's only script is `<script type="module" src="/assets/app.js">`. That is what lets the policy keep `script-src 'self'` with no `'unsafe-inline'`, and a test asserts it rather than leaving it to a browser console. |
| Nothing is kept in web storage | The session cookie is `HttpOnly` and `__Host-` prefixed, so the page can neither read nor set it — there is no credential for it to stash. The CSRF token is read fresh from its cookie for each request rather than cached, because the server rotates it at sign-in. Asserted. |
| **Text through `textContent`, never `innerHTML`** | Everything shown can contain a server message or something a person typed. The policy stopping an injected `<script>` from running is not a reason to hand one to the parser. |
| The silences are kept | Sign-up, resend and reset-request all answer 202 whether or not anything happened, because reporting the difference would say who has an account. The pages say *"if that address…"* accordingly, and one wrong-password message covers a wrong password, an unknown account and a locked one — the UI does not improvise a more helpful wording than the server was willing to give. |
| **The token stays in the address bar** | The page does not echo it into its own HTML — that would put it in the DOM for any injected script and into anything that caches the document. `Referrer-Policy: no-referrer` (§7a) stops it leaving in a `Referer`, which is what makes a token in a query string tolerable at all. |

**The policy widened for this, deliberately and minimally** (§7a): every
relaxation from `default-src 'none'` is `'self'`, so the website's own scripts
and styles load and **nothing external can** — no CDN, no font service, no
analytics. There is no `'unsafe-inline'` for script and there must never be
one: an injected `<script>` tag, the ordinary shape of cross-site scripting,
still does not run.

**What this does not settle.** D9 — the `__Host-` prefix and `Secure` have
still never been exercised by a real browser, and cannot be over plain HTTP on
localhost, which is why the `dev` profile still overrides `secure-cookie` to
false. Serving the pages is the prerequisite for testing that, not the test.

## 8. Database — V10 to V13

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
  `expires_at` (the hourly sweep below).

`V11__rate_limit_hits.sql` adds **one table and nothing else**, for §6:

```
rate_limit_hits(id, bucket, hit_at)
```

- One row per counted attempt, deleted once it leaves its window.
- `bucket VARCHAR(320)` — the limiter's key, namespaced by the limiter that
  owns it. It holds a client address or an e-mail address. **There is no
  column for anything secret**, which is the first thing the migration test
  asserts.
- Index on `(bucket, hit_at)` for the hot path — count and prune one bucket —
  and on `hit_at` alone for the sweep, which crosses every bucket.
- No foreign key: a bucket is not always an account, and a count that
  disappeared when an account did would be a way to clear one's own limit.

`V12__denied_access_tokens.sql` adds **one table and nothing else**, for the
L4 fix in §4:

```
denied_access_tokens(id, token_id UNIQUE, denied_at, expires_at)
```

- One row per sign-out, deleted once the token it denies is long expired.
- `token_id VARCHAR(64)` — the `jti`, **stored as it is and not hashed**,
  which is the one place this differs from `refresh_tokens` and the V10 link
  tokens. The reasoning is in §4; the length is
  `AccessTokenDenyList.MAX_TOKEN_ID_LENGTH`, comfortably above the 36
  characters of the UUIDs `JwtService` issues, and an id longer than it is
  refused rather than truncated into one that could never match.
- **No `username` column**: a row says one token is dead, not who ended it.
  The migration test asserts the column list for that reason.
- `UNIQUE (token_id)` so a second logout of the same token cannot add a second
  row — the store inserts with `ON CONFLICT DO NOTHING`, which is why it is
  idempotent without a read-then-write race.
- `CHECK (expires_at > denied_at)` can never fire: the store returns early for
  a token that has already expired, because the decoder refuses that token
  without any help from this table.
- No foreign key, for the same reason as `rate_limit_hits`: there is no
  account column to point one at, and a sign-out is not account state.

`V13__login_failures.sql` adds **one table and nothing else**, for the L3 fix
in §6:

```
login_failures(id, username → users, client_address, failed_count,
               locked_until NULL, first_failure_at, last_failure_at)
```

- One row per (account, address) pair, `UNIQUE (username, client_address)` —
  so the count is upserted (`ON CONFLICT … DO UPDATE`) rather than read then
  written, which would lose an increment exactly when two attempts arrive
  together.
- `locked_until` is **nullable**: a pair that has failed a few times is not
  locked, and the distinction matters.
- `client_address VARCHAR(64)` — the remote address, 45 characters at the
  very longest in IPv6 text form. An address beyond the column is truncated
  rather than dropped, which is safe here and would not have been for a token
  id: two addresses sharing a 64-character prefix would merely **share** one
  budget, which is more restrictive, never less.
- `username` → `users(username)` `ON DELETE CASCADE ON UPDATE CASCADE`: an
  account's failure rows are its own, like its refresh tokens (V6), and the
  account sweep (risk D11) is not blocked by them.
- **Nothing is indexed by address alone.** The pair index serves every read
  and write the sign-in path makes; `last_failure_at` serves the sweep.
  Nothing asks what one address has been attacking, and an index for it would
  be an invitation to start.
- **No column for the password, the identifier that was typed, or a user
  agent.** The lock needs a pair, a count and two timestamps.

### The account sweep — risk D11

`DbUserAccounts.deleteNeverVerifiedAccounts` runs **daily** and deletes
accounts that were created by website sign-up, never proved an address and
were never used. Retention: **7 days** (`SIGNER_WEB_UNVERIFIED_RETENTION`).

This is the **only statement in the application that removes a `users` row**,
so it is a native query in `UserRepository` with every guard spelled out in
SQL. An account goes only when all of these hold:

| Guard | Keeps out |
|---|---|
| `email_verified_at IS NULL` | anyone who proved their address |
| `status = 'ACTIVE'` | **`PENDING_INVITE`** — invitation identities, which also have no verified address and no password, and must never be swept — and `DISABLED`, which an operator chose |
| `last_login_at IS NULL` | anything ever signed in to |
| `created_at < cutoff` | anything inside the retention window |
| no row in `workflow_signers` | a signer, even an unverified one |
| not `workflows.created_by` | the creator of a signing request |
| not `workflows.claimed_turn_username` | whoever holds a turn |

**What the database does on its own.** The last three are also foreign keys
with `NO ACTION`, so PostgreSQL would *refuse* such a delete — the guards are
repeated in the query so the sweep **skips** those rows instead of failing the
whole statement every night. What does cascade with the row is only the
account's own secrets: refresh tokens (V6), launch codes (V8) and the two V10
token tables. `audit_events.actor` and `document_versions.created_by` are free
text by design, so no history is ever rewritten by this.

**It has an off switch**, unlike the token sweep below: set the retention to
`PT0S` and no account is ever deleted. Deleting accounts is the one
destructive piece of housekeeping here, so an operator can decline it.

### The token sweep

`WebAccountService.deleteExpiredTokens` runs **hourly** and deletes rows that
expired **more than a day** ago, from both tables. Same shape as
`DbRefreshTokenStore.deleteExpired` and `LaunchCodeService.deleteStaleCodes`,
with a later initial delay (20 min) than either so the three do not all run at
once on start-up. It logs counts only — never a hash.

Nothing depends on it for safety: an expired token is refused on every path,
and a consumed one is retired by `consumed_at`. It exists because the tables
otherwise only ever grow — every sign-up, resend and reset request adds a row,
and sign-up is open to anyone (risk D1). The one-day grace period after expiry
is for answering a support question ("was a link ever issued?"), not for
correctness.

### The counter sweep

`DbRateLimitStore.deleteOldHits` runs **hourly** too (initial delay 25 min,
again offset from the others) and deletes counter rows older than **a day**,
whatever their bucket. A bucket is otherwise pruned only by its own next
request, so an address that knocks once and never returns would keep its row
for ever. The retention is far longer than any window in use, deliberately:
housekeeping must never be the thing that refuses a request. Like the token
sweep it cannot bring the server down with it — a failure is logged as a class
name and the next run tries again.

### The deny-list sweep

`DbAccessTokenDenyList.deleteExpired` runs **hourly** (initial delay 35 min,
the last of the six) and deletes rows whose token expired more than **an hour**
ago. Nothing depends on it for safety in either direction: an expired token is
refused by the decoder, so a row that outstays its welcome denies something
already dead, and a row swept early would un-revoke a token — which is what
the hour of grace is for. It is far longer than any clock difference between
instances ought to be, and the row is worth nothing after the token's own
`exp`, so there is no reason to cut it finer. Without the sweep the table would
grow by one row per sign-out for the life of the deployment.

### The failure sweep

`DbUserAccounts.deleteIdleLoginFailures` runs **hourly** (initial delay 40
min, the last of the seven) and forgets pair rows idle for more than **a
day**. A pair is otherwise cleared only by a successful sign-in from that
address, so an attacker who tries once and never returns would keep a row —
and the address in it — for ever. The retention is far longer than any lock,
so this can never be what forgives one; it is the thing that stops the table,
and the record of who tried what from where, from being permanent.

## 9. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SIGNER_WEB_MAIL_LIMIT` | `3` | Account e-mails per recipient per 15 min |
| `SIGNER_WEB_SIGNUP_LIMIT` | `5` | New accounts per client address per window |
| `SIGNER_WEB_SIGNUP_WINDOW` | `PT1H` | That window |
| `SIGNER_WEB_UNVERIFIED_RETENTION` | `P7D` | How long a never-verified, never-used account is kept. `PT0S` switches the sweep off |
| `SIGNER_WEB_SESSION_ANON_IDLE` | `PT5M` | Idle timeout before sign-in |
| `SIGNER_LOGIN_RATE_LIMIT` | `20` | Per-address requests per window, per bucket |
| `SIGNER_LOGIN_RATE_WINDOW_SECONDS` | `60` | That window |
| `SIGNER_LOGIN_MAX_FAILURES` | `5` | Wrong passwords from **one address** before that address is locked out of that account (§6) |
| `SIGNER_LOGIN_ACCOUNT_MAX_FAILURES` | `50` | Wrong passwords from **all addresses together** before the account itself is locked. Must be ≥ the figure above; the server refuses to start otherwise |

One option is not an environment variable, deliberately: `--signer.unlock-account=<username>`
is passed on the command line, to a process started for it, because it is an
action and not a setting (§6). Left in a deployment's configuration it would
unlock that account on every restart, so the runner logs a warning when it
finds itself in a process that is also serving requests.

`SIGNER_LOGIN_MAX_FAILURES` is not new, but what it counts is: it used to
count against the account and now counts against the (account, address) pair,
with the new variable above taking over the account-wide role at a much
higher default. A deployment that had set it keeps the same per-attacker
behaviour and stops locking out its own users.

Nothing new for the deny-list: how long an entry lives is the access token's
own `exp`, which `SIGNER_SERVER_ACCESS_TOKEN_MINUTES` already sets, and where
the list is kept is `SIGNER_DB_ENABLED`, as below.

No new variable chooses where the limits are counted: `SIGNER_DB_ENABLED`
already does. With a database they are counted in it and shared; without one
they are counted on the heap, because there is nowhere else (§6). The two
store beans are mutually exclusive conditions on that one property rather
than a bean plus a fallback — a context that silently got the in-memory store
while running several instances would believe it had limits it does not have.

The links in the e-mails are built from `InvitationLinkResolver.baseUrl()`,
the same base the invitation links already use, as
`<base>/verify-email?token=…` and `<base>/reset-password?token=…`. Both paths
are now **served** by this application (§7b) — they answer with the website's
entry point instead of leading nowhere. What they answer with is still a
placeholder: the page that would ask for a new password is D2's work, and the
endpoint behind it has been there and tested since this phase began.

## 10. Test results

Full suite on **JDK 21.0.12.1 LTS** (the version the build targets) with a real
PostgreSQL 16.15.

```
Tests run: 709, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS — 5 min 59 s
```

| | After Phase C | After Phase D1 |
|---|---|---|
| Found | 532 | **709** (+177) |
| Passed | 524 | **701** |
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
| `WebAccountHttpTests` | 22 | sign-up, the taken-address case changing nothing, weak password, verify once and never again, unknown vs malformed vs **expired** (24 h) being identical, resend, the unverified gate on workflow creation, **the gate on all five signing actions, the download included** (and that listing is still allowed), reset identical answers, reset ending every session and refresh token, **an expired reset link** (30 min), password change keeping this session and ending the others, change needing the current password and a session, the anonymous/signed-in idle timeouts, **the password-change notice on both paths and none on a refused attempt**, nothing secret in the log |
| `WebAccountNoticeTests` | 4 | the notice is best-effort: a mail server that throws does not undo the password change or the revocations, a wrong current password sends nothing, an account with no address on file is simply not notified, and the reset/change distinction reaches the mailer |
| `SigningActionPolicyTests` | 5 | the five covered actions, that listing and reading a task are not covered, that each path is paired with its method (and `HEAD` of the document is not a way round it), and that the match is anchored so no prefix or suffix smuggles a path past it |
| `UnverifiedSigningGateHttpTests` | 4 | one test per gated step against a **real** workflow where the account holds a prepared slot — claim, download (and `HEAD`), submit — each refused `EMAIL_NOT_VERIFIED` with nothing changed in the database, plus the counterweight: the same account, verified, runs the whole desktop flow through to a cryptographically **valid** signature |
| `WebAccountRateLimitHttpTests` | 3 | the per-recipient mail cap (still 202, no further mail), the `\|account` bucket reaching 429 with `Retry-After` **without spending the sign-in budget**, and the same for the `\|csrf` bucket |
| `WebSignUpThrottleHttpTests` | 2 | the fourth sign-up from one address is refused 429 with `Retry-After` **and writes no `users` row**, with the shared limiter left wide open so only the throttle can be what fired; and that the throttle covers sign-up alone — resend, reset-request, verification, sign-in and the CSRF bootstrap all still answer normally once it is spent |
| `IdentityRateLimiterTests` | 8 | the sliding window itself, against the in-memory store: counting, the `Retry-After` it reports and how it shrinks, the budget returning after the window, identities counted separately and case-insensitively, a blank identity allowed, a meaningless limit refused at construction, and — new — that two limiters sharing one store do not spend each other's budget, and that a nameless limiter or one with no store is refused |
| `DbRateLimitStoreLiveTests` | 9 | not the window again, but everything the move to the database was *for*: the wiring really resolves to the shared store, a row per counted attempt and none for a refusal, `Retry-After` shrinking, the window sliding and the rows leaving with it, buckets counted separately, **another instance seeing the budget already spent**, **24 concurrent callers admitting exactly the cap and no more**, **a counted attempt surviving the rollback of the caller's transaction**, and the sweep deleting what is long dead while leaving a fresh row |
| `DbRateLimitStoreFailOpenTests` | 3 | the fail-open decision, needing no PostgreSQL because the point is that there is none: an unreachable database allows the attempt rather than throwing into the filter chain (three times over, so a cached broken connection would show), the sweep survives it too, and a *misconfigured* limit is still refused loudly before any connection is tried |
| `PasswordChangeEndsTokensHttpTests` | 4 | risk D7 over HTTP, on its own database because it changes seeded passwords: an access token that worked a moment earlier is refused 401 after the change, the account can sign in again **immediately** afterwards and its new token is not caught by the guard, a **task session** from a launch code is refused the same way (the 30-minute half of the risk), and one account's change leaves another's token alone. Each aligns to a second boundary first, and asserts that precondition rather than assuming it |
| `DbUserAccountsTests` | +4 | the rule itself without a database: a token from before the change rejected and one from after it allowed, **a token from the change's own second allowed** (the documented trade-off, pinned down so it cannot drift by accident), a null column invalidating nothing, and an unknown account or absent `iat` answering false |
| `LoginLockoutPerAddressLiveTests` | 5 | Phase 2 risk **L3** from the request down, which is the part neither the mocked nor the service-level tests can show: that the desktop API locks **the address the request came from** and not the account, so the holder still signs in; that the website and the API count against **one** pair rather than five attempts each; that a forwarded header **cannot choose the lockout key** (five different `X-Forwarded-For` values, one real address, one row); that the sweep forgets an idle pair without touching the account; and that deleting an account takes its failure rows with it |
| `LoginFailureMigrationLiveTests` | 5 | V13 on a fresh database, the exact seven columns (**nothing for the password, the typed identifier or a user agent**) with a nullable `locked_until` and the 64-character address bound, the UNIQUE pair that forces the upsert (and that a second address for the same account is a second budget), the cascade from `users` together with the **absence** of an index on the address alone, and that V13 leaves an existing **V12** database — including an account lock that was in force — intact |
| `FrontendServingHttpTests` | 13 | the same-origin serving of §7b, and in equal measure what it did **not** open: the entry point at `/` and `/index.html`, the two e-mail pages and the website's own three answering with it, the reset page **not echoing its token** into the HTML, `no-referrer` and the policy on those pages, the entry point loading its code as **files and not inline** (an inline block would be blocked in a browser and nowhere else), the script and stylesheet served with types a browser will accept (a module served as `text/plain` is refused outright), the script talking to **this origin only** and keeping nothing in web storage — then an unlisted path still refused, **a valid access token not making one readable either** (`denyAll()` denies whoever asks, so the 401 an anonymous caller gets is not an invitation to sign in and try again), the API still answering JSON 401 rather than the HTML page a catch-all forward would have returned, a missing asset being a 404 and not HTML, and **no `Access-Control-*` header** coming back for a foreign `Origin` |
| `SecurityHeadersHttpTests` | 5 | the headers of §7a against a real server: the invitation path sends `no-referrer` and the policy (risk L9), so does a JSON route, and so does an unauthenticated **401** — which answers from `JsonSecurityErrors` rather than a controller, so it is worth proving it has not bypassed the header writer; that the policy permits **no script and no eval**; and that Spring Security's own defaults (`nosniff`, `DENY`, `no-store`) are still in force |
| `SignPageRendererTests` | +1 | that neither page variant carries script, an event handler, an image or an external stylesheet — which is what lets the policy be as strict as it is, and what fails if someone adds one |
| `WebAccountHttpTests` | +1 | the **website** chain sending the same two headers, because it is a second `headers()` call and two chains quietly disagreeing about a security header is the failure that would otherwise go unnoticed |
| `AccountLockNoticeTests` | 9 | the notice (§6), with mocks rather than a database because what is being tested is which events send a message: the account-wide lock tells the holder, **a per-address lock tells nobody** however many times it fires (the test that matters — otherwise the notice is a mail-bombing tool), a second account lock inside the window is not a second message, an unverified address is not written to, a mailer that throws leaves a refused sign-in refused rather than a 500, a server with no mailer configured sends nothing, the body carries **no link** and names **no address** while saying when the lock lifts and that the password itself was not given up, and the notice cap spends no other limit's budget |
| `AccountUnlockLiveTests` | 8 | the operator unlock (§6): both halves of a lockout cleared at once and the account usable from everywhere again, that it **grants nothing** — the wrong password is still wrong afterwards — that an account which is not locked clears nothing and is not an error, that it is idempotent, that an unknown name (and the easy mistake of passing the e-mail address) is reported as unknown rather than as a successful no-op, that a blank name unlocks nothing, that only the named account is unlocked while the same address stays locked out of another, and that a server **without** the option does not carry the runner bean at all |
| `AccountUnlockRunnerLiveTests` | 3 | the command-line option an operator actually types. The locked-out account is seeded before the context starts, because the runner does its work as the context comes up: the lock and the address lock are gone by the time the tests look, the password is untouched and `password_changed_at` still null (so an unlock invalidates no token), and the bean is present precisely because the option is set |
| `WebAccountHttpTests` | +2 | the self-service half: a reset **unlocks** an account that was locked out both ways, so the holder is back in without waiting out a 24-hour address lock — and, the distinction that keeps that from being a hole, that merely *requesting* a reset (no token, no secret) clears nothing |
| `AuthenticationPersistenceLiveTests` | +2 | the account-wide backstop (enough failures across enough addresses still lock the account, after which even an address with no failures of its own is refused), and the escalation against the real table: **coming back after a lock costs twice as long**, the count standing at 7 rather than having been reset, and the holder still signing in throughout. Its four existing lockout tests were **rewritten**, not added to — they asserted that the fifth failure locks the account and that locking resets the count, which is exactly what L3 changed. One of them now reads as the holder's case: two mistypes cost nothing and a success clears the escalation |
| `DbUserAccountsTests` | +8 | the rules without a database: a failure counted at both scopes with the right threshold at each, a locked address refused the right password **while another address is let in**, an expired pair lock no longer blocking, an attempt with no address counting against one shared pair rather than being exempt, an over-long address truncated rather than dropped, the startup refusal when the account-wide threshold is below the per-address one — and for the delay: the whole schedule including the cap and that it cannot overflow however long an address keeps at it, that the cap never outlives the row carrying it, that the schedule scales to a **chosen** `lock-minutes` and `max-failures` (both below and above the fixed steps), and that the Nth delay is taken from the count already on the row |
| `LogoutDeniesAccessTokenHttpTests` | 5 | Phase 2 risk **L4** over HTTP, in file mode: an access token refused the moment its session is signed out of — on `/api/auth/me` and on a workflow route, so it is the filter and not one endpoint — while **the same account's other session is untouched** (the distinction that rules out reusing D7's mechanism); signing out twice still a 204 with the token still denied; a logout that presents **no** bearer revoking only the refresh session, which is the honest limit of the fix rather than a hidden one; and a garbage or signature-tampered bearer still not blocking the logout, because `/api/auth/logout` is public on purpose |
| `AccessTokenDenyListTests` | 7 | the list itself, with no database and no server: one token denied and no other, denying twice being one entry, an already-expired token not recorded at all (the one row the table's CHECK would refuse), an entry dropped once the token it denies would have expired, an absent id or expiry writing nothing, an id too long refused rather than truncated — and that the length it accepts comfortably covers the UUIDs `JwtService` issues |
| `DbAccessTokenDenyListLiveTests` | 7 | what the database implementation is *for*: that database mode really resolves to it rather than silently to the heap, that the entry is in the **table** so another instance over the same database sees it, that the row holds the jti and the token's own expiry and nothing about the account, that `ON CONFLICT DO NOTHING` makes a second logout one row, that an expired token is never written, that an over-long id is refused before it reaches the column, and that the sweep deletes what is long dead while leaving a live entry alone |
| `DeniedTokenMigrationLiveTests` | 6 | V12 on a fresh database, the exact four columns (**no column for the account**) with `timestamptz` and the 64-character bound, the UNIQUE that forces the upsert, the CHECK refusing an entry that would outlive nothing, both indexes and why each exists, and that V12 leaves an existing **V11** database — accounts and counter rows — intact |
| `RateLimitMigrationLiveTests` | 5 | V11 on a fresh database, the exact three columns (no column for anything secret) with `hit_at` not-null and `timestamptz`, both indexes and why each exists, that the `pg_advisory_xact_lock(hashtext(…)::bigint)` cast the store depends on resolves on this server, and that V11 leaves an existing **V10** database — accounts and tokens — intact |
| `NeverVerifiedAccountSweepLiveTests` | 10 | one test **per guard** of the account sweep (§8): the abandoned sign-up deleted with its tokens cascading, and a verified account, an invitation identity, a disabled account, one ever signed in to, one inside the retention window, one holding a signer slot, one owning a workflow and one holding a claimed turn all surviving — the last three asserting the sweep **skips** them rather than failing — plus zero retention being a no-op |
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

**What moving the counts to the database cost the test suite.** In memory the
buckets lived in the application context, so every test class started with an
empty one for free. In the database they outlive the context, and the classes
that enable a database nearly all share one (`signer_test`) and all reach it
from `127.0.0.1` — so they now share a single `addr:127.0.0.1` budget, and one
class spending it made the next answer `429` to its first request. Two classes
did fail that way on the first full run: `LaunchExchangeRateLimitTests`, which
asserts the exact sequence of statuses a budget of 4 produces and so needs to
start from zero, and `GuestSigningSessionE2ETests`, which was simply a victim.

The fix belongs in the tests, not in the store: that shared budget is the
whole point of the change in production. `RateLimitTestSupport.clear` empties
the table, and the seven classes that reach a limited endpoint call it in
`@BeforeEach`. They are now *less* order-dependent than before, since they no
longer rely on getting a fresh application context. It is the same move
`LaunchCodeHttpTests` already made for the launch-code issue throttle.

**What V12 cost the test suite**, in the same spirit. The V11 migration test
asserted that `flyway_schema_history` held exactly 11 successful rows, and V12
made that 12 — so adding a migration failed the test for the migration before
it. The assertion was meant as *"V11 added itself and re-ran nothing"*, which
is a claim about one step; written as a total it was really a claim about how
many migrations exist. Both it and the new V12 test now migrate to their own
target version, so each pins its own step and a V13 will not repeat this. The
older V9 and V10 tests never had the problem: they compare the table list
before and after instead of counting history rows.

## 11. Residual risks

| # | Risk | Severity | Note |
|---|---|---|---|
| D1 | **Sign-up is open and creates a row before the address is proved** | Low (was Medium) | Decision D1 in the plan. Now throttled to 5 accounts per address per hour (§6), down from the shared bucket's 20 a minute, and an unverified account can do nothing (§5) while its tokens are swept (§8). **What remains:** the throttle is per address and in memory, so a botnet with many addresses, or a restart, gets a fresh budget; and nothing deletes an account that never verifies — see D11. It also lands harder on shared addresses than the per-minute limits do (risk C6): an office behind one NAT address gets 5 sign-ups an hour between everyone, which is the number to raise first if that is the deployment. A CAPTCHA belongs with the sign-up page (D2). |
| ~~D11~~ | ~~Nothing deletes an account that never verifies~~ | **FIXED** | §8. Swept daily after 7 days, with every guard in SQL and a test per guard. **What remains:** an address stays unusable for its retention week — a sign-up for it is answered 202 and silently does nothing because the row is still there, so someone who mistyped and never got the e-mail must wait or ask. Narrowing that needs the sign-up path to distinguish "taken by an unverified stranger" from "taken", which is an enumeration question, not housekeeping. |
| D2 | **E-mail delivery is the whole security of both flows** | Medium | A reset link in a mailbox is the account. Nothing here can fix a compromised or mis-delivered mailbox; the 30-minute life and single use are the mitigation. No second factor exists. |
| ~~D3~~ | ~~Both identity limits are in memory, per instance~~ | **FIXED** | §6. Every limit — both identity caps, the sign-up throttle and the per-address filter (so Phase C risk **C6** with it) — now counts through `RateLimitStore`, backed by `rate_limit_hits` (V11) wherever a database is configured: one budget across instances, and it survives a restart. **What remains:** a server running *without* a database still counts on the heap, because it has nowhere else to count — that is a real deployment mode, not an oversight, and the in-memory store is the same algorithm. And the limits now fail **open** on a database outage; the reasoning, and why failing closed would be worse, is in §6 and in `DbRateLimitStoreFailOpenTests`. |
| ~~D4~~ | ~~Only workflow creation is gated on a verified address~~ | **FIXED** | §5. `SigningActionPolicy` + `AuthenticatedRequestFilter` now gate claim, the document download, signatures, certificate binding and launch-code issuing for account tokens, and Phase B's three existing `email_verified_at` reads were routed through `maySign` so there is one rule in one place. Guests are deliberately exempt. **What the original wording got wrong:** the launch-code path was never ungated — `issue`, `exchange` and `taskSessionState` each already read `email_verified_at` directly. The gap was the account-token signing routes, and the duplication that hid it. The document download was gated in a follow-up: it had first been left readable as "looking around", which was the wrong call — it is step one of signing. |
| ~~D5~~ | ~~Nothing deletes expired tokens~~ | **FIXED** | §8. `WebAccountService.deleteExpiredTokens` sweeps both tables hourly, deleting rows that expired over a day ago, through the `deleteExpiredBefore` queries that had been written and never called. |
| ~~D6~~ | ~~The sliding window is implemented twice~~ | **FIXED** | §6. Folded into `RateLimitStore` while D3 was being fixed, which is the change that made it safe to do: both callers had to move onto one store anyway, so sharing the algorithm stopped being a behaviour change and became the point. `LoginRateLimitFilter` and `IdentityRateLimiter` are now thin callers, and their existing tests passed unchanged — which is the evidence that the behaviour of the tested one was preserved. |
| ~~D7~~ | ~~A reset does not end desktop *access* tokens, only refresh tokens~~ | **FIXED** | §4. `passwordChangedSince` refuses any bearer token issued before the change, covering the task session (30 min) as well as the access token (15 min). No migration and no new per-request read — the column and the account lookup were both already there. **What the original wording got wrong:** it justified the gap as "the same window Phase 2 accepted for a disabled account", but that window had already been closed in Phase 2 itself — `AuthenticatedRequestFilter` has re-checked the account on every bearer request since the commit that introduced it (`f03fff1`), which is exactly why this fix was nearly free. **What remains:** a sub-second window, reasoned about in §4 — `iat` is whole seconds, so a token issued inside the change's own second cannot be told from one issued just after it, and is allowed to stand rather than break every fresh sign-in. |
| ~~D8~~ | ~~No notification when a password changes~~ | **FIXED** | §4. Both paths e-mail the holder, with no link in the message, best-effort so a mail failure cannot undo the change. **What remains:** nothing is sent when an address itself changes, because an account's address still cannot change at all (§12); and the notice says *when*, not *from where* — there is no stored client address to tell them, and inventing one would mean logging what visitors' addresses are. |
| D9 | **`__Host-`/`Secure` on localhost still unverified in real browsers** | Low (dev only) | Phase C risk **C1**, unchanged: this phase still has no frontend and no browser. D2 must resolve it and delete the dev cookie override. |
| ~~D10~~ | ~~No CSP, and still no pages to apply one to~~ | **FIXED** | §7a, with Phase 2 risk **L9** and Phase C risk **C7**. `default-src 'none'` on both chains, permitting no script at all, plus `Referrer-Policy: no-referrer` so a one-time token cannot leave an invitation page in a `Referer`. The premise of the original wording was wrong, incidentally: there *were* pages to apply one to — the two server-rendered pages behind the e-mailed links, which are precisely the ones carrying a token in the URL. **What remains:** D2 has to widen the policy when it has scripts and styles of its own, and `style-src 'unsafe-inline'` is a real concession to the renderer's inline stylesheet and its two inline `style=` attributes. |
| ~~C2~~ | ~~No sign-up, verification or password reset~~ | **FIXED** | This phase. |
| ~~C6~~ | ~~The per-address limiter is in memory, per instance~~ | **FIXED** | §6, with D3 — the address filter moved onto the same store, so the two were one fix. It is the hottest path of the four, and the one where the shared count matters least on its own (the account lockout already spans instances) and most in combination: an attacker alternating instances no longer gets a fresh per-address budget on each. |
| ~~C8~~ | ~~Website sessions and desktop refresh tokens are independent on a password change~~ | **FIXED** | §4. |
| ~~C9~~ | ~~The CSRF bootstrap creates a session for an anonymous visitor~~ | **FIXED** | §7: 5-minute idle timeout before sign-in, promoted at sign-in, plus its own rate-limit bucket. |
| L3 | **Locking someone out of their own account is still possible, but dearer** | Low (was Medium) | §6. The 5-failure lock is per (account, address) pair, so an attacker locks out their own address and the holder is unaffected, and it is **progressive** — the count survives the lock, so repeat offenders double their way to a 24 h cap instead of getting five fresh attempts every fifteen minutes for ever. The account-wide lock stays as a backstop at 50, because without it rotating addresses would make guessing unbounded. And when it does happen there are two ways back without waiting — a successful password reset unlocks the account by itself, and an operator can clear one with `--signer.unlock-account` — while the holder is told it happened, once per 6 hours and only for the account-wide lock, because a notice on the per-address lock would have been a way to mail-bomb them. **What remains:** 50 failures from 10 addresses still lock the holder out, and IPv6 makes 10 addresses cheap, so this is a price rise and not a closure. All four parts Phase 2 listed for L3 are now built, which is not the same as closing it: the delay does not help against a rotating attacker (each address starts its escalation at zero), and the unlock and the notice are recovery rather than prevention. What would stop it is a reputation or proof-of-work input to sign-in, or a CAPTCHA with the frontend (D2) — none of which was on the list. That one needs something not built here: a reputation or proof-of-work input to sign-in, or a CAPTCHA with the frontend (D2). It also introduces the one piece of data this phase otherwise avoided: a stored (account, address) pair, swept after a day. |
| ~~L4~~ | ~~Signing out ends the refresh token only; the access token keeps working for up to 15 minutes~~ | **FIXED** | §4. The `jti` deny-list Phase 2 prescribed, scoped to the one token presented with the logout so that one device signing out does not sign out the others, and failing **closed** because a revocation check that answers "not revoked" when it does not know is not one. **What remains:** the server can only refuse a token it has been shown, so a client that posts the refresh token alone still leaves its access token alive — the desktop client was changed to send the bearer, and a third-party client would have to do the same; and in file mode the list is per instance, so behind two such servers a logout reaches one of them. Both are visible in the tests rather than papered over. |

## 12. What was deliberately not done

- **The account website exists** — D2, in part. Sign-up, confirmation,
  sign-in, reset request, reset confirmation, password change and sign-out all
  have pages now (§7b), served same-origin with no build step. **What is still
  missing is everything else:** no CAPTCHA on sign-up (the last of D1), no
  workflow pages at all because there are no workflow routes under `/web/api`
  to call (Phase E), and **nobody has opened any of this in a real browser** —
  which is D9, and the reason the `dev` profile still overrides
  `secure-cookie` to false. The tests here assert what the server sends, not
  what a browser does with it.
- No change-of-address flow: an account's e-mail cannot be changed at all.
  The verification token already carries the address it was sent to, so the
  flow has somewhere to build from.
- No "sign out everywhere" button and no session list (Phase C risk C4) —
  `WebSessionRegistry` now makes both straightforward for the website. The
  deny-list (§4) would **not** be the mechanism for the desktop half of it:
  denying every bearer token of an account means knowing their ids, and
  nothing records which are outstanding. That one wants a per-account
  "valid from" stamp, which is what risk D7 already built — a reset is a
  sign-out-everywhere that happens to change the password too.
- No account deletion, no admin routes, no role management. **Still true after
  the L3 unlock** (§6), and the reason it is a command-line option rather than
  an endpoint: adding the route would have meant adding an operator
  credential — a role system, or a static shared token with no rotation and no
  attribution — to perform one operation. The option needs shell access to a
  host that can reach the database, which is what an operator already has.
- No CAPTCHA — it belongs with the sign-up page (D2), on top of the throttle.
- No shared cache for the rate limits. Redis or similar would suit the shape
  of the data better than a table does — it expires rows by itself, and these
  rows are worth nothing after their window — but it would be a second piece
  of required infrastructure for counters, and the database is already there
  and already required by every endpoint behind these limits. The table is
  swept hourly instead (§8).
- No notice when an *address* changes — an account's address cannot change at
  all yet, so there is nothing to notify (D8 covers password changes only).
- No workflow routes under `/web/api` — Phase E.
- No change to the desktop login, guest invitations, launch codes or signing.
