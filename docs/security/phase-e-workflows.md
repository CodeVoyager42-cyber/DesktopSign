# Phase E — workflows on the website

The website can manage an account (Phase D) but knows nothing about
documents. Phase E gives it the workflow routes under `/web/api`, in three
parts:

| Part | Scope | State |
|---|---|---|
| **1** | The read side: the two lists, and one workflow's state, audit trail and revision chain | **done**, this document |
| 2 | The write side: create a workflow (multipart), invite, resend, revoke, cancel | planned |
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

## 3. Still to do

- **Part 2**: `POST /web/api/workflows` (multipart), `{id}/invitations`
  (POST and GET), `{id}/cancel`, `invitations/{id}/resend` and `/revoke`.
  CSRF is already enforced on every state-changing `/web/api` request, so the
  tests there are about the owner-only rules and about CSRF being required
  rather than merely configured.
- **Part 3**: `GET /web/api/workflows/{id}/download` — the final signed PDF,
  for the owner and the signers, once the workflow is `COMPLETED`. New
  behaviour rather than a thin call, and the one place in Phase E where
  document bytes leave the server, so it gets its own part.
- **The screens**: the dashboard and the status timeline specified in
  `../frontend-design.md` §6, which these routes exist to feed.
