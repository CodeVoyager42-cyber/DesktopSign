# Phase F — the hand-off from the website to the desktop

Status: **part one built and tested.** The website can now open the desktop
application at one signing task. The installer's protocol registration and
the retirement of the desktop's own sign-in are not done; §4 says why that
order.

Phase B built the credential and the exchange (`phase-b-launch-codes.md`):
a 256-bit single-use code, two minutes, issued to a signed-in account for one
slot of one workflow, exchanged by the desktop for a task-scoped session.
What was missing was a website that could ask for one.

---

## 1. The route the website needed

Phase B exposed issuing at `POST /api/workflows/{id}/launch-codes`, on the
**bearer** chain, deliberately — so the mechanism was testable before the
website existed. The website has no bearer token: it has a `__Host-` session
cookie and a CSRF token, and neither chain parses the other's credential
(`phase-c-web-sessions.md` §3). So Phase F adds

```
POST /web/api/workflows/{id}/launch-codes    session + CSRF
  → 201 {"deepLink": "signflow://launch?v=1&c=<code>", "expiresInSeconds": 120}
     Cache-Control: no-store
```

One line over `LaunchCodeService.issue`, the same call the bearer route makes.
Every rule therefore applies here by construction rather than by repetition:

| Refusal | When |
|---|---|
| `403 WORKFLOW_NOT_ACCESSIBLE` | the caller holds no slot of this workflow |
| `403 EMAIL_NOT_VERIFIED` | the address is unconfirmed (Phase D gate) |
| `409 CERTIFICATE_NOT_BOUND` | the slot has no certificate yet — a launch never enrols one (Phase B review H2) |
| `409 NOT_YOUR_TURN` | somebody else's turn |
| `409 INVALID_WORKFLOW_STATE` | already signed, still a draft, or finished |
| `429 TOO_MANY_REQUESTS` | more than five codes a minute |

`Cache-Control: no-store` because the answer *is* the credential. The bearer
route has always sent it; this one must too, and a test asserts the header
rather than trusting the controller to keep it.

**And `GET /web/api/app-info`**, which serves the configured
`SIGNER_DOWNLOAD_URL` (and the application's name) to the hand-off screen.
The website is a static export, so it cannot read a property that is decided
at deployment; the invitation page has rendered the same value since Phase 3.
Session-protected, not public — a route that needs no session needs a rate
limit of its own (Phase C risk C9) — and blank is served as null so a page
testing for a link need not know how absence is spelled.

## 2. The screen

`/open?id=<workflowId>`, described in `../frontend-design.md` §5d. The
security-relevant decisions:

- **The code never enters the DOM.** The page navigates by assigning
  `location.href` and holds the link in a ref. An `<a href>` would put a
  credential where a right-click can copy it.
- **No code is reused.** When the clock runs out the screen asks for a new
  one; it never replays the old link. Issuing also revokes the previous
  unused code for the same slot, so an abandoned screen leaves nothing usable
  behind.
- **The deep link is not bookmarkable.** `/open?id=…` carries no code — it
  asks for one on arrival — so a reader who bookmarks this page bookmarks a
  request, not a credential.
- **Refusals are the server's words.** The screen adds nothing except a
  fallback sentence for a refusal that arrives with no message, and it offers
  Retry only for refusals a retry could change.

## 3. Tested

| Test | Asserts |
|---|---|
| `aSignerAsksTheWebsiteForTheLinkThatOpensTheirDesktop` | 201, `signflow://launch?v=1&c=`, 120 seconds, `Cache-Control: no-store`, and a second ask yields a different code |
| `onlyASignerOfTheWorkflowGetsALaunchLink` | the workflow's creator, who signs nothing, gets 403; a stranger gets the uniform refusal |
| `aSlotWithNoCertificateCannotBeOpenedFromTheWebsite` | 409 with `CERTIFICATE_NOT_BOUND` — the shape every workflow the website creates has |
| `everyStateChangingRouteNeedsItsCsrfToken` | the launch-code route joins the list: a session without its CSRF token is 403 |
| `theHandOffScreenCanAskWhereToGetTheApplication` | `app-info` answers 200 with a session and 401 without; an unconfigured link is null |
| `web/lib/handoff.test.ts` (7) | the countdown, including a tab asleep for an hour; which refusals offer Retry |

`FrontendServingHttpTests` covers `/open` being its own document, as it does
every page in `PAGES`.

## 4. Not done, and the order of the rest

**The first-time signer still needs the desktop's own sign-in.** A launch
never enrols a certificate, so a slot with no card registered cannot be
opened from the website at all — and every workflow the website creates
starts unbound. The signer must open the task in the desktop application
while signed in there, connect their card, and only then can the website hand
them off. This is not a gap in the screen; it is the H2 remediation working
as designed, and it means the website cannot yet carry a signer end to end.
Closing it is a product decision about where a certificate is first
registered, not a bug to fix in a page.

**The installer registers nothing** (audit finding F2). `scripts/package.sh`
and jpackage produce a build that has never claimed the `signflow://` scheme,
so on a machine where only the installed copy exists, these links open
nothing. `scripts/register-signflow-protocol.cmd` does it for development,
per-user under `HKCU`. Until the installer does the same, the hand-off works
only where that script has been run.

**Retiring the desktop's sign-in, wizard and start-up gate** stays last, and
gated: decision D5 keeps them until the website is in production, and the two
items above are both reasons the desktop still has to be able to stand alone.

**Nothing has been seen in a browser.** The route is proven over HTTP and the
countdown by unit test; the screen itself — the clock ticking, the scheme
navigation, the browser's own "Open SignFlow?" dialog — has not been watched
once. That is the next thing worth doing, and it needs a browser, not another
test.
