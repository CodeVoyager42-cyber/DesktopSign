# Phase E — workflows on the website

The website can manage an account (Phase D) but knows nothing about
documents. Phase E gives it the workflow routes under `/web/api`, in three
parts:

| Part | Scope | State |
|---|---|---|
| **1** | The read side: the two lists, and one workflow's state, audit trail and revision chain | **done**, §1 |
| **2** | The write side: start a workflow, invite a slot, track, resend, revoke, cancel | **done**, §3 |
| **3** | The final signed PDF, once the workflow is complete — gap G6 | **done**, §4 |

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

## 4. Part 3 — the finished document, and gap G6

`GET /web/api/workflows/{id}/download`, over
`RemoteWorkflowService.getCompletedDocument`.

G6 (phase-3-authorization.md) read: *the owner cannot download the document
unless it is their turn*. There was exactly one download, `GET
…/document`, and it hands the current revision to the one signer whose turn
it is — which is right for what it is for, and leaves the person who sent a
contract out with no way to get the signed copy back. Before Phase E that was
a missing feature. With a website in front of it, it is the obvious thing to
click.

So there are now two downloads, and they are different in kind:

| | `…/document` (desktop) | `…/download` (website) |
|---|---|---|
| Hands over | the current revision | the final revision |
| To | the signer whose turn it is | the creator and every signer |
| When | while signing is in progress | only once `COMPLETED` |
| Next step | a signature comes back | nothing; it is finished |
| Unverified account | refused — step one of signing | allowed; see below |

**Before the last signature there is deliberately nothing to give out.** A
half-signed revision is a working copy, and handing it out as "the document"
invites it to be forwarded, printed and treated as the agreement.
`409 INVALID_WORKFLOW_STATE` says so. Anyone who is not in the workflow at all
gets the uniform 404 instead — asserted byte-for-byte against a made-up id,
including that the document's name is not in the refusal.

**The Phase D gate is not applied here, and deliberately.** Its stated
reasoning (phase-d-accounts.md §5) is that handing the document to an account
that has not proved its address serves no purpose when it cannot submit the
result. Here there is no result to submit: the document is finished, and what
the download is for is keeping your own copy of something you signed or sent.
In practice the question barely arises — a workflow cannot reach `COMPLETED`
unless every slot signed, and `createWorkflow` is gated — but the rule is
stated rather than left to that coincidence.

### A header-injection fix in passing

Writing the route turned up something in the existing one. A document's name
is the uploader's text, validated only for being non-blank, and
`/api/…/document` put it straight into `Content-Disposition`:

```java
.header("Content-Disposition", "attachment; filename=\"" + documentName + "\"")
```

A name containing a `"` ends the filename early and leaves the rest to be
read as further header parameters; one containing CR/LF is a header-splitting
attempt, which the container turns into a 500 rather than a download. Both
routes now go through `WorkflowOperations.attachment(…)`, which replaces
anything outside `[A-Za-z0-9 ._-]` with `_` and falls back to
`document.pdf` when nothing usable is left. Spaces are kept, because
filenames have them.

The cost is real and worth naming: accents and non-Latin scripts are
flattened too. The fix for that is RFC 5987's `filename*`, which deserves its
own tests for how each browser reads it rather than being slipped in behind a
security fix.

### Test results

`WebWorkflowDownloadHttpTests`, over real HTTP against a real PostgreSQL.
**5 tests, 0 failures.** The workflows here are genuinely signed — one slot
bound to the mock card's certificate, signed through the same
`WorkflowSigningService` the desktop uses, submitted and accepted — because a
faked `COMPLETED` would have tested the route and not the thing the route is
for.

| Test | Asserts |
|---|---|
| `theFinishedDocumentGoesToEveryoneWhoTookPart` | the sender, who signed nothing, gets 200, `application/pdf` and a `%PDF-`; the signer gets the identical bytes |
| `beforeItIsFinishedThereIsNothingToDownload` | 409 `INVALID_WORKFLOW_STATE` for sender and signer alike while it waits |
| `aStrangerGetsExactlyTheAnswerForAWorkflowThatDoesNotExist` | 404, the same body a made-up id gets, and the document's name nowhere in it |
| `withoutASessionThereIsNoDownload` | 401 |
| `aDocumentNameCannotCarryAnythingIntoTheHeader` | quote, CR, LF, colon and `=` flattened; an all-punctuation name becomes `document.pdf`; `Q3 report (final).pdf` keeps its spaces |

## 5. The screens

All of them are built: the dashboard (`../frontend-design.md` §5a), the status
timeline (§5b), and the sending side — the create form and the owner's
invitation panel (§5c). Between them they call **ten** of the twelve routes
above.

Two are not called from the website, each for a stated reason:

| Route | Why no screen |
|---|---|
| `POST /{id}/invitations` | It only accepts the address the slot's account already has — `EMAIL_MISMATCH` otherwise, so that a slot cannot be handed to a stranger. The one case it would serve is a slot assigned by username that was never e-mailed, and the website cannot know that account's address, so it would be asking the owner to guess one the server then checks. The desktop keeps it. |
| `GET /{id}/revisions` | Hashes and sizes of the revision chain. Nothing on a reader's screen needs them: the timeline says a new version was stored, and the download gives the document. It stays for the desktop and for anyone auditing over the API. |

The timeline is the one worth noting here, because what it chose **not** to
show is a security decision rather than a design one: it renders an event's
type, actor and time, and never the audit `detail`. See §6.

The "Sign" hand-off screen belongs to Phase F, not here — Phase E stops where
the desktop takes over.

## 6. A finding the timeline turned up: audit detail carries addresses

Writing a screen over `GET /web/api/workflows/{id}/audit` meant reading what
that answer actually contains, and one field in it is wider than the route
that serves it.

`AuditView.detail` is free prose written at the call site. Most of it is
operational — *"Revision 3 accepted (54123 bytes)"*, *"Certificate bound to
signer 2 (a1b2c3d4)"* — but `INVITATION_CREATED` records:

```java
appendAudit(wf, AuditEventType.INVITATION_CREATED, invitedBy,
        "Invitation created for signer " + signerOrder + " (" + normalizedEmail + ")");
```

The audit route is readable by **every participant**, not just the owner. The
invited address of a slot, on the other hand, is otherwise owner-only: it is
served by `GET /{id}/invitations`, which checks ownership. So a signer who
reads the audit of a workflow they are in can learn the e-mail addresses of
their co-signers, which the invitation route would refuse them.

**Not introduced here, and not widened here.** The field has been in that
answer since the trail existed; the desktop fetches the view
(`WorkflowApiClient.audit`) but no screen in the product renders `detail`, so
nothing has ever displayed it. The timeline keeps that true — it writes its
own words from the event type — which is why this is a finding and not an
incident.

**The fix belongs in the server, not in a screen.** Two candidates, in
preference order:

1. **Structured detail.** Replace the prose with the fields the readers
   actually need (`signerOrder`, revision number, byte count) and leave the
   address out of the trail entirely. The address is already in
   `invitations`, where the ownership check is.
2. **Redact per reader.** Keep the row and have the route blank `detail` for
   a caller who is not the owner.

(1) is better: an audit trail that records a personal address in a field
nobody may read has stored it for no reader. It is a change to what the
server writes, so it wants its own commit and its own test — a participant
reading the audit of a workflow they are in must not find a co-signer's
address in it — and it is recorded here rather than done in passing behind a
frontend change.

### 6.1 Fixed, with both halves

**The address is no longer written.** Two call sites put one in a detail:
`INVITATION_CREATED` in `InvitationService.invite`, and `INVITATION_EXPIRED`
in `InvitationExpirySweeper`. Both now record the **slot number** only.
Nothing is lost by it: an invitation can only ever be addressed to the slot
account's own address — the `EMAIL_MISMATCH` guard in the same method enforces
exactly that — so a slot's address is a property of the slot, and
`invitationStatus` serves it to the people entitled to it. Every other detail
in the trail already used slot numbers.

**And the detail of three event types is served to the creator only.** That is
what covers the rows already in the table: rewriting an append-only audit
trail to hide what it recorded would be worse than the exposure, and a
migration that scrubbed it would be exactly that. The three are
`INVITATION_CREATED` and `INVITATION_EXPIRED`, whose old rows name an address,
and `EMAIL_DELIVERY_FAILED` — which is **current**, not legacy: it carries the
mail transport's own error text, and an SMTP rejection routinely names the
recipient. No scrubbing of somebody else's error message would be dependable,
and the creator is the only reader who can act on a delivery failure anyway.
The event type, the actor and the time are unchanged for everybody.

**The first version of this rule was "all prose to the creator", and it was
wrong.** Two Phase B tests caught it within the same suite run:
`LaunchCodeHttpTests` reads the trail *as the signer* and asserts on
`DESKTOP_SESSION_OPENED` and `DESKTOP_SESSION_REVOKED` detail — which carries
the device and app version of the session that was opened. That is not
incidental: it is the whole of the F3 mitigation in
`../architecture/website-launch-code-plan.md`, where the website shows the
signer *"opened on device X, app vY at hh:mm"* with a "this wasn't me" revoke.
A blanket rule would have hidden a signer's own session from them to protect a
co-signer's address — one exposure traded for a worse one. Hence a named list
of event types, with the reason recorded beside it.

Tested by `aCoSignerCannotReadAnAddressOutOfTheAuditTrail`: a second signer
reads the trail of a workflow they are in and finds no address of any account
in it, still sees `INVITATION_CREATED`, and gets none of its prose — but does
still get the detail of an event that never named an address, which is what
pins the rule as a list rather than a blanket. The creator reads the same
trail, still gets *"Invitation created for signer 1"*, and that sentence no
longer names an address either.

**What this does not fix.** A signer can still learn *that* slot 1 was
invited and when, which is proper to a shared workflow. And `actor` is a
username, which participants see anyway. The exposure that is closed is the
address.

## 7. Three things only a live run found

The screens were green in every suite — 36 frontend tests, the whole Java
suite — and then the server was started for real, an account registered
through the website's own routes, and a document sent. Three defects surfaced
in the first five minutes. Each is recorded with what it cost, because all
three are the kind of thing a test suite cannot see: two were wrong *words*
and one was a query that no test asserted either way.

### 7.1 A cancelled document vanished from its sender's dashboard

`RemoteWorkflowService.ownedBy` ended with `&& wf.status() !=
WorkflowState.CANCELLED`. Reproduced against the live server: `/owned` held
the workflow before the cancel and was one shorter after it.

The dashboard groups `COMPLETED`, `CANCELLED` and `FAILED` as *Finished*, and
for a workflow whose creator does not also sign it, `owned` is the **only**
list it can appear in — `participating` is slot-based. So that group could
never receive a cancelled document, and the cancel button on the document
screen left the reader looking at a page that had just fallen out of their own
dashboard.

Fixed by removing the condition: every lifecycle state is listed, cancelled
included. No test asserted the exclusion, which is why it survived; there is
one now (`aCancelledWorkflowStaysInTheListOfWhatYouSent`) asserting the
workflow is in `/owned` both before and after, and still not in
`/participating`.

### 7.2 The timeline said "Created" twice

`createWorkflow` appended `WORKFLOW_CREATED` twice: once for the creation, and
again for *"N signer(s) must bind a certificate before the document is
prepared"* — a different fact under the same event type, distinguished only by
the prose `detail`. The status timeline shows an event's **type** and never
its detail (§6, and `lib/timeline.ts`), so it could only say *Created* twice
and never say what the document was waiting for.

Fixed with a new `AuditEventType.CERTIFICATES_PENDING`. No migration: the
column is `VARCHAR(50)` with no `CHECK`, and the first `WORKFLOW_CREATED`
stays, so `WorkflowFileStore`'s creator recovery is untouched.
`aDraftSaysWhyItIsADraftRatherThanSayingCreatedTwice` counts exactly one of
each over HTTP. The timeline now reads *"Every signer must register a
certificate before this is prepared"*, in the story rather than the trail,
because why a new document is sitting still is what its sender came to find
out.

**This is the audit-`detail` decision earning its keep in both directions.**
Not rendering `detail` is what exposed the overloaded event type — a screen
that printed the prose would have looked fine and hidden the modelling fault.
It is also what made the fault matter at all. The answer was to fix the model,
not to start printing the prose.

### 7.3 A draft claimed it had not been sent

`STATE_WORDS.DRAFT` was *"Not sent yet"*. But invitations go out when the
workflow is created, and a workflow stays `DRAFT` only until every slot has a
certificate bound — which the signers do from their own machines, after the
e-mail has arrived. So every freshly sent document told its sender it had not
been sent, which is the one wording here that could make somebody resend
something unnecessarily. `blockedReason` is null in that state, so the
timeline's pending row also fell through to its signature wording and claimed
a signer was being asked to sign a document that had not been prepared.

Now *"Waiting for certificates"*, and the pending row says *"Waiting for a
certificate from X"*, *"Waiting for your certificate"* or *"Waiting for the
signers' certificates"*. Three tests in `timeline.test.ts` and one in
`dashboard.test.ts`.

### What this says about the test suite

Nothing here was a logic error, which is why 726 Java tests and 31 frontend
tests all passed over it. 7.1 was a query whose behaviour no test had an
opinion about; 7.2 and 7.3 were sentences that were wrong about the state they
described. A suite asserts what someone thought to assert, and nobody asserts
that a sentence is *true* — so the live run is not a formality to do once the
tests are green. It is a different instrument.
