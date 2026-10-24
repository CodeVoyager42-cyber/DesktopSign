# Phase E — workflows on the website

The website can manage an account (Phase D) but knows nothing about
documents. Phase E gives it the workflow routes under `/web/api`, in three
parts:

| Part | Scope | State |
|---|---|---|
| **1** | The read side: the two lists, and one workflow's state, audit trail and revision chain | **done**, §1 |
| **2** | The write side: start a workflow, invite a slot, track, resend, revoke, cancel | **done**, §3 |
| 3 | The final signed PDF, once the workflow is complete — gap G6 | planned |

Signing is in none of them. The website never downloads a document in order
to sign it and never submits a signature: the certificate stays on the
signer's smart card and the desktop application does the signing. Phase F is
the hand-off that opens it.

## 1. Part 1 — the read side

Five routes, in `server/web/WebWorkflowController`:

| Method | Path | Answers | Desktop equivalent |
|---|---|---|---|
| GET | `/web/api/workflows/owned` | workflows this account created | same |
| GET | `/web/api/workflows/participating` | every workflow it is a signer of, in every state | `/signing-tasks` |
| GET | `/web/api/workflows/{id}` | one workflow's state | same |
| GET | `/web/api/workflows/{id}/audit` | what happened, in order | same |
| GET | `/web/api/workflows/{id}/revisions` | the revision chain: hashes and sizes, no bytes | same |

Each method is one line over `RemoteWorkflowService`, passing
`Authentication.getName()` — the session's username — where the desktop's
controller passes the username the bearer filter resolved. Same service, same
arguments, same refusals.

### Why a second controller and not a second mapping

`WorkflowRestController` already answers all five of these on `/api`. Adding
`/web/api` to its `@RequestMapping` would have been shorter and is the wrong
shape:

- The two live in **different security domains** (Phase C §3). This one is
  reached with a session cookie and a CSRF token, that one with a bearer JWT,
  and neither chain parses the other's credential.
- They **learn who is calling differently** — from the `Authentication` the
  session chain loaded, or from the request attribute the bearer filter sets.
  One controller would have to ask both, and "whichever is present" is an
  authentication decision made in a controller.
- One set of annotations for both domains means a mistake in a route's method
  or matcher exposes **both at once**. Two thin controllers cost about forty
  lines and keep that blast radius at one domain.

### Why there is no new authorization code

Every method hands the service a username and returns what comes back, so the
Phase 3 rules hold here by construction rather than by repetition: a stranger
gets the uniform 404 — byte-for-byte the answer for a workflow that does not
exist — and each list contains only what the caller takes part in.

Nothing was added to `WebSecurityConfig` either. Its last rule before
`denyAll()` is `/web/api/**` → `ROLE_WEB_USER`, so these routes were already
behind a session the moment they existed. That is the intended direction: a
new website route is refused until someone lists it, never open until someone
remembers to protect it.

### The unverified gate stays where it is

An account that has not confirmed its address may read all five of these. That
is the Phase D rule (phase-d-accounts.md §5), not an exemption: the gate is on
the **steps of signing**, and `createWorkflow` carries it in the service,
which is why it will hold for part 2 without a line being written here. Being
told that you have been asked to sign, and seeing how far the document has
got, is not a step of signing — and refusing it would leave an unverified
account staring at an empty dashboard with no way to understand why.

## 2. Test results

`WebWorkflowReadHttpTests`, over real HTTP against a real PostgreSQL, on its
own throwaway database. **5 tests, 0 failures.**

| Test | Asserts |
|---|---|
| `eachListHoldsOnlyWhatTheCallerTakesPartIn` | the owner's workflow is in `owned` and not in `participating`; for its signer, the other way round |
| `aStrangerGetsExactlyTheAnswerForAWorkflowThatDoesNotExist` | 404 on all three id routes, with a body identical to a made-up id's, and the workflow in neither of the stranger's lists |
| `withoutASessionEveryRouteIsRefused` | 401 on all five without a cookie |
| `aDesktopBearerTokenOpensNothingHere` | a genuine access JWT for a genuine signer opens `/api/workflows/{id}` with 200 and every `/web/api` route with 401 |
| `anUnverifiedAccountMayStillReadWhereItsTaskStands` | 200 on all three id routes, and the workflow listed |

The matrix is repeated against the cookie domain on purpose. The controller
cannot differ from `/api` without the service differing — but "cannot differ"
is exactly how a route ends up on the wrong chain, and proving it costs one
file.

## 3. Part 2 — the write side

Six routes. Four join `WebWorkflowController`; the two that address an
invitation by its own id are `WebInvitationController`, because the path is
`/web/api/invitations/{id}/…` and not under a workflow — the same shape
`/api/invitations` has.

| Method | Path | Answers |
|---|---|---|
| POST | `/web/api/workflows` | 201: the workflow, plus the links that were e-mailed |
| POST | `/web/api/workflows/{id}/invitations` | 201: the invitation, with the link that just went out |
| GET | `/web/api/workflows/{id}/invitations` | where each invitation stands, slot by slot |
| POST | `/web/api/workflows/{id}/cancel` | 200: the workflow, now `CANCELLED` |
| POST | `/web/api/invitations/{id}/resend` | 200: the fresh link; the old token stops working |
| POST | `/web/api/invitations/{id}/revoke` | 204 |

### One copy of each multi-step operation

Three of these are not single service calls. Starting a workflow has to
prepare the e-mail-addressed slots **before** the workflow exists and send
their invitations **after** it does. Inviting a slot has to check the caller
may see the workflow at all before validating a slot number against how many
it has — otherwise the error message describes a workflow the caller cannot
see. Reading invitation status runs the access check, then lets
`InvitationService` re-check ownership.

Each of those orders carries a rule, and part 1 warned about exactly this:
two chains with a copy each is how a check gets tightened on one and
forgotten on the other. So the sequences moved into
`api/WorkflowOperations`, which both controllers call, and
`WorkflowRestController` lost about sixty lines to it. What stays in a
controller is what belongs to its own domain: resolving the caller from its
own credential, and the HTTP status of the answer.

`WorkflowOperations` holds no authorization rules of its own. Every path
starts at `RemoteWorkflowService.getWorkflow`, whose uniform 404 is the
Phase 3 guard, and invitation ownership stays in `InvitationService`.

### What the routes did not need

- **No CSRF code.** The Phase C chain already enforces a token on every
  state-changing `/web/api` request. The tests assert that a session alone
  — the cookie, no `X-XSRF-TOKEN` — is refused 403 on all five POSTs, and
  that nothing happened: no workflow was created and the existing one is not
  cancelled.
- **No verification gate.** `createWorkflow` carries the Phase D rule in the
  service, so an unverified account is refused `403 EMAIL_NOT_VERIFIED` on
  this route without the route mentioning it. Asserted, because that is the
  kind of thing a refactor silently drops.
- **No ownership checks.** Resend and revoke take a numeric invitation id,
  which is guessable; both pass the caller's username to
  `InvitationService`, which re-checks who created the workflow. The test
  proves another owner's id gets the same answer as an id belonging to
  nobody, and that the invitation still works afterwards for its owner.

### Test results

`WebWorkflowWriteHttpTests`, over real HTTP against a real PostgreSQL.
**5 tests, 0 failures.**

| Test | Asserts |
|---|---|
| `aWorkflowStartedHereIsOwnedAndItsInvitationsAreTracked` | 201; it is in `owned`; the e-mailed link is in that answer and never in a later read |
| `everyStateChangingRouteNeedsItsCsrfToken` | 403 on all five POSTs with a session but no token, and no state changed |
| `onlyTheOwnerInvitesTracksAndCancels` | a signer and a stranger are refused; a stranger's cancel is the uniform 404; the owner's succeeds |
| `anInvitationIdIsNotACapability` | another owner's id gets the answer a non-existent id gets, and the invitation survives it |
| `anUnverifiedAccountCannotStartAWorkflow` | `403 EMAIL_NOT_VERIFIED`, and nothing created |

One finding from writing these, worth knowing for the screens: **both cookies
rotate at sign-in**, the session id against fixation and the CSRF token with
it, so a client has to keep the pair the login response sets rather than the
pair the `/web/api/csrf` bootstrap handed out. `assets/api.js` already reads
the token fresh from its cookie per request, so the website does the right
thing; a test that cached it did not.

## 4. Still to do

- **Part 3**: `GET /web/api/workflows/{id}/download` — the final signed PDF,
  for the owner and the signers, once the workflow is `COMPLETED`. New
  behaviour rather than a thin call, and the one place in Phase E where
  document bytes leave the server, so it gets its own part.
- **The screens**: the dashboard and the status timeline specified in
  `../frontend-design.md` §6, which these routes exist to feed.
