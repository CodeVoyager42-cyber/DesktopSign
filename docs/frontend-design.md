# SignFlow website — design system

What the website is built from, and what the later screens are planned to be.
The security reasoning behind the same-origin decision, the
Content-Security-Policy and the wording of refusals lives in
`security/phase-d-accounts.md` §7a and §7b; this document is the visual and
structural side of the same work.

**The implementation is `web/`** — Next.js, exported to static files and
served by the application itself; see `frontend-nextjs.md` for the build, the
routing and what choosing it cost. Everything below describes the system
those components render, and the token values are unchanged from the
hand-written stylesheet they came from.

**Not DocuSign.** The patterns below are drawn from the same family of
enterprise signing products, but nothing is borrowed from anyone's identity:
no imitation of a logo, a typeface licence, a violet brand colour, or
anyone's copy.

## 1. Tokens

All of these are CSS custom properties on `:root` in `web/app/globals.css`
(they moved there unchanged when the website became a Next.js export —
see `frontend-nextjs.md`). Light
theme only, deliberately — the direction specifies a white background and a
measured light palette, and inventing a dark theme would mean inventing
contrast decisions nobody checked. `color-scheme` is pinned to `light` so
form controls follow, and a dark theme waits to be directed rather than
guessed.

| Token | Value | Contrast | Used for |
|---|---|---|---|
| `--ink` | `#0F1B2D` | 16.9:1 on white | body text, headings |
| `--ink-muted` | `#4A5567` | **7.3:1** on white | supporting text, labels |
| `--accent` | `#0E7C66` | **5.1:1** on white, and white on it | the single primary action, links, focus ring |
| `--accent-strong` | `#0A6453` | 6.9:1 | hover and active |
| `--accent-tint` | `#E9F2F0` | ~9 % accent over white | badges, success messages |
| `--band` | `#062A23` | near-black of the accent | the footer band |
| `--surface` | `#FFFFFF` | — | sheets and cards |
| `--surface-sunken` | `#F5F8F7` | — | the page behind them |
| `--line` | `#DDE3E2` | — | every border, 1 px |
| `--danger` | `#A3242A` | 6.5:1 | refusals |
| `--warn` | `#7A5200` | 5.6:1 | "not confirmed yet" |

**Teal, not blue.** Both candidates measured the same (5.1:1), so the choice
was identity: a product blue is what every other application in this space
reaches for, and the teal is recognisably SignFlow's without being anyone
else's.

**Radius** is `8px` everywhere, with one exception: the left-bordered message
strips are square on the border side, because a radius there reads as a
detached chip rather than an annotation.

**No shadows.** Separation is by tinted surface — a white sheet on
`--surface-sunken`, with a 1 px `--line` — which stays honest at any zoom
level and needs no dark-theme equivalent.

## 2. Type

Inter, **self-hosted** (`font-src 'self'`; no font service is reachable under
the policy, by design). The scale is 64 / 40 / 24 / 16 px:

| Step | Size | Weight | Tracking | Used for |
|---|---|---|---|---|
| Hero | 64 px | 300 | −0.03em | reserved for a marketing page; unused so far |
| Title | 40 px | 300 | −0.03em | one per screen, the `<h1>` |
| Section | 24 px | 400 | −0.02em | `<h2>` |
| Body | 16 px | 400 | 0 | everything else |
| Button | 14 px | 600 | 0 | padding 12 × 16 |

At ≤ 480 px the title drops to 28 px and the section to 20 px; nothing else
changes.

> **The font ships in the build.** It comes from
> `@fontsource-variable/inter` (OFL-1.1) and is emitted under
> `/_next/static/media`, so it is served from this origin and `font-src
> 'self'` permits it with no font service in the policy. All seven subsets
> are kept, because the dashboard shows the names of the people you sign
> with and those are not always Latin. Until the website became a Next.js
> export this pointed `@font-face` at two files nobody had added, and the
> stack quietly fell through to the platform font.

## 3. Patterns

- **One primary action per screen.** A filled `--accent` button, full width
  inside the sheet. Anything else on the screen is a text button or a link.
  This is why changing a password is its own page rather than a second form
  on the account screen.
- **Sheet** — a white panel with a hairline border holding one form. Max
  width 29 rem, centred in a 1080 px content column.
- **Card** — title, up to two lines of supporting text, and a `… ›` link.
  The account screen is two of them; the dashboard will be many.
- **Badge** — a pill for state: accent tint for *Confirmed*, warm tint for
  *Not confirmed yet*.
- **Message strip** — one `role="status"` region per screen with
  `aria-live="polite"`, so a refusal or a confirmation is announced rather
  than only coloured.

**Refusals never reveal whether an account exists.** This is a design
constraint, not only a server one: sign-up, resend and reset-request all
answer `202` whether or not anything happened, so the pages say *"if that
address…"* and mean it, and one message covers a wrong password, an unknown
account and a locked one. The temptation in UI work is to be more helpful
than that; being more helpful here would undo the server's work.

## 4. Accessibility

- **WCAG AA** on every pairing in the token table, including white on accent.
- **Visible focus** — a 2 px `--accent` ring with a 1–2 px offset on inputs,
  buttons and links. Nothing removes an outline anywhere.
- **Keyboard** — real `<button>` and `<a>` elements only, labels bound with
  `for`/`id`, `autofocus` on the first field of each screen, and submit on
  Enter because every form is a real `<form>`.
- **375 px** — a single column with 16 px gutters; no horizontal scroll.
- `prefers-reduced-motion` is honoured (there is nothing animated yet, so
  this is a guard against a later regression).

## 5. Screens that exist

| Screen | Path | Notes |
|---|---|---|
| **The public page** | `/` | hero, proof strip, role tabs over feature cards, how it works, security, closing CTA |
| **Documents** | `/documents` | three groups with counts; the wider column |
| **One document** | `/document?id=…` | where it stands, what happened to it in order, and — for its owner — who was invited |
| **Send a document** | `/send` | the file, the signers, the order; the only page that uploads |
| Sign in | `/sign-in` | one message for every kind of refusal |
| Create an account | `/sign-up` | 202 either way; the confirmation page is worded for both |
| Forgotten password | `/forgot-password` | same |
| Confirm address | `/verify-email?token=…` | acts on arrival, then reports |
| Set a new password | `/reset-password?token=…` | refuses an absent token before asking for anything |
| My account | `/account` | two cards, a state badge, sign out as a text button |
| Change password | `/change-password` | its own screen; falls back to sign-in without a session |

Every path is one line in `FrontendRoutingConfiguration.PAGES`, which
`SecurityConfig.PUBLIC` reads as well, so a page cannot be routable but
refused — or permitted and never routed.

## 5a. The dashboard, as built

Three groups, each with its count in the heading and the action-required
count in the tab title, so a request that arrived while the tab sat open is
visible without reading the page.

| Group | Contains |
|---|---|
| **Action required** | not finished, and the server says the caller's slot is the one being waited for (`myState.waitingForMe` or `waitingForClaim`) |
| **Waiting for others** | not finished, and waiting for somebody else |
| **Finished** | `COMPLETED`, `CANCELLED` or `FAILED` |

Two of those words were wrong until a live run said so (`../security/phase-e-workflows.md`
§7). `DRAFT` read *"Not sent yet"*, when it actually means the invitations
have gone and the signers have not registered their certificates yet — it is
*"Waiting for certificates"* now. And the *Finished* group could never
receive a `CANCELLED` document at all, because the server left cancelled
workflows out of the owner's list; that was a server fix, not a wording one.

Each row is the card pattern: document name, *"from X · 2 of 3 signed"*, and
a state line or badge. Empty groups collapse to a single muted line rather
than an empty panel — a dashboard whose first impression is three empty boxes
reads as broken. The column is the full 1080 px `--measure` rather than the
29 rem the forms use, because a row carries a name, a line and an action.

**Two deviations from §6's plan, both deliberate:**

- *"Waiting for others"* was specified as "sent by this account, not yet
  complete". That would have hidden a document the viewer had **already
  signed** while others still had not — it is neither theirs to act on nor
  theirs by sending. The group is now everything in flight that is not
  waiting for the viewer, whoever sent it, which is what the heading says
  anyway.
- *"Completed"* became **"Finished"**, because cancelled and failed
  workflows are finished without having completed, and they have to live
  somewhere. Each row carries its own state, so the distinction is still
  visible.

**`Open ›` on every row**, now that there is a screen for it to open (§5b).
It waited for that screen rather than pointing at nothing. Beside it, a
finished row offers *Download the signed PDF ›* in the same line, separated
by the `·` the links pattern uses; an action-required row adds *"To sign it,
open SignFlow on your computer."* — worded that way round since the row's own
link is called *Open*. Signing needs the certificate on the signer's own
card, so no button here could do it; the hand-off that opens the application
is Phase F.

The download is a plain `<a download>` rather than a fetch: the browser
fetches it with the session cookie, same origin, and the bytes never pass
through the page's JavaScript. It is also what keeps the client-side router
from swallowing the click and trying to render a PDF as a page.

## 5b. The status timeline, as built

A vertical rail, one dot per row in accent (done), a hollow ring in `--line`
(pending) or `--warn` (blocked), read from the audit events the server
records — which is why the screen can state the **order** of what happened
rather than inferring it from the current state. The rail is each row's left
border and not a line behind the list, so it stops at the last dot: a line
continuing past the end reads as "more to come", which on a finished document
would be a lie.

**The id is in the query string** — `/document?id=<workflowId>` — because the
website is a static export. A path segment per document would need a page
built per workflow, and those are created at runtime; the alternative is the
catch-all forward `FrontendRoutingConfiguration` refuses, which would answer
every mistyped path with an HTML 200. So it is one more line in `PAGES`, and
the id travels the way the e-mail links' tokens already do.

**Three deviations from §6's plan, all deliberate:**

- *"One row per event"* became **two layers**. The trail is complete and
  internal: certificate bindings, revision writes, placement records,
  ignored duplicate submissions. Shown in full by default, twenty rows of
  bookkeeping bury the story of the document. So the story — created,
  invited, opened, signed, finished, plus **everything that went wrong** —
  is the list, and *"Everything that was recorded (n)"* is a disclosure
  under it. Nothing is dropped; the default reading is just the part that is
  about the agreement. `CERTIFICATES_PENDING` is in the story too: why a
  freshly sent document is sitting still is exactly what its sender came
  here to find out. That event type exists because of this screen — the
  server used to record a second `WORKFLOW_CREATED` for it
  (`../security/phase-e-workflows.md` §7.2).
- **An event type this version has never heard of is shown**, in the story,
  with its name made readable (`WORKFLOW_FROZEN` → "Workflow frozen"). The
  server can grow an event without the website changing, and a screen that
  silently dropped what it did not recognise would be wrong in the direction
  that hides things.
- **The last row is the current state, not the last event.** The trail says
  who was activated; the workflow says who is being waited for *now*, and
  after a released turn those are different answers. It carries no
  timestamp, because it has not happened. For a `DRAFT` workflow it names
  what is actually missing — *"Waiting for a certificate from X"* — rather
  than falling through to the signature wording, which claimed somebody was
  being asked to sign a document that had not been prepared yet.

**`detail` is never rendered.** The server's audit detail is written for
whoever reads an audit trail — *"Revision 3 accepted (54123 bytes)"*, a short
fingerprint — and the words on screen are the website's own, from the event
type. There is also a reason not to relax that: the detail of
`INVITATION_CREATED` **used to contain the invited e-mail address** of a slot,
and `GET /web/api/workflows/{id}/audit` is readable by every participant, so
rendering it would have shown one signer another's address. Writing this
screen is what found that; it was fixed in the endpoint rather than worked
around in the page (`../security/phase-e-workflows.md` §6.1 — the address is
no longer written, and the detail of the three event types that could name
one reaches the creator alone, which covers the rows already written).

The rule stands anyway, and not only for that reason: `detail` is prose for
whoever reads an audit trail, and a screen that printed it would have shown
*"Revision 3 accepted (54123 bytes)"* to somebody who wanted to know whether
their contract had been signed. It would also have hidden §7.2 — the event
type that was recorded twice under one name — behind prose that read
correctly.

Dates are written once per day, times on every row — a column of identical
dates is noise, and a row with only a time reads as "later the same day",
which is what it means. Both come from `at()`, which formats as the account
e-mails do (*24 October 2026 at 21:51*) and answers null for a timestamp it
cannot read, so a row appears without one instead of showing *Invalid Date*.

## 5c. Sending a document, as built

One screen to start a signing request, and the owner's half of the document
screen to run it afterwards.

**The form.** The PDF, its name, the signers, the order. The name defaults
from the chosen file and keeps its `.pdf`, because this name is what the
finished document is downloaded as; typing over it stops the default from
following a later change of file. Signer rows are added one at a time, and a
row holds **an e-mail address or an existing username** — the server reads an
`@` as "invite this address" and anything else as "this account exists
already", so the field says so rather than making the reader choose a mode
first. Blank rows are dropped and the numbering closes up behind them: a slot
order with a hole in it is a workflow the server refuses for a reason nobody
on this screen could see.

**What the form does not check.** Whether an address is well formed, whether
a username exists, whether two slots name one person. All of that is the
server's judgement and it answers with its own sentence, which the screen
shows as given — the rule `lib/api.ts` already keeps. What is checked here is
only what the form itself can know: a file was chosen, it is a PDF, there is a
name, somebody is named to sign. A second copy of the server's rules in the
browser is a second thing to keep in step, and the screen would still have to
believe the server.

**No certificate is ever named from the browser.** Every slot is created
unbound, because a certificate lives on the signer's smart card. So a new
workflow truthfully reports that it is waiting for certificates, and the
document screen shows that as its pending row.

**An unconfirmed account is told, not shown a form.** `createWorkflow`
carries the Phase D gate in the service, so the screen reads
`emailVerified` and offers the account screen instead of a form that would
fill in and then fail.

**There is no confirmation screen.** On success the page goes to the new
document's own screen, where the server's audit trail already says *created ·
a signer was invited · the invitation went out*. A success panel here would be
a second account of the same event — and the one that goes stale.

**The invitation links are not displayed.** The create call answers with the
exact links that were e-mailed, once, and this page drops them. A link is the
credential that signs as that person: e-mail is the channel it was addressed
to, a browser on a shared desk is not, and when delivery fails the answer is
*Send it again* rather than a link to copy out of a page. The desktop shows
them because it has no delivery tracking to offer instead; the website has.

**The owner's panel**, on the document screen, is one card per slot: the
address, where its invitation stands, when the link expires, when it was
opened. *Send it again* replaces the previous link, which stops working at
once; *Withdraw it* leaves the slot unsigned and nobody invited to it. Signed
wins over every other wording and offers no actions at all — an invitation row
stays `PENDING` after its slot signs, and a *Withdraw* button beside a
signature would be the worst mistake available here.

How the page knows the reader is the owner: it asks for the invitations and
sees whether the server allows it. That read is owner-only and a signer is
refused with `WORKFLOW_NOT_ACCESSIBLE`, which the page treats as "not yours"
and says nothing about. The alternative — inferring ownership from the
dashboard's lists — would be the page deciding for itself what it may show.

**Inviting a *new* slot is not offered**, and that is from reading the route
rather than from laziness: `POST /{id}/invitations` only accepts the address
the slot's own account already has (`EMAIL_MISMATCH` otherwise, deliberately,
so a slot cannot be handed to a stranger). The one case it would serve is a
slot assigned by username that never got an e-mail — and the page cannot know
that account's address, so it would be asking the owner to guess one the
server then checks. It stays a desktop route.

**Cancelling** sits last, below a rule, quiet until it is asked for, and it
asks: *"Signatures already given stay in the record, nobody can sign it after
this, and it cannot be undone."* It is shown only to the creator or whoever is
being waited for, and only while the workflow is unfinished — the same two
conditions the server applies, so the button is never offered where it would
be refused.

Every action re-reads the whole screen afterwards. Not a courtesy: a refusal
usually means the state moved while the page sat open — the slot signed, the
workflow completed — and then the fresh answer is the only honest thing to
show. The timeline picks up the new audit event from the same read.

## 6. Planned, not built — Phase F

Recorded here so the shape is agreed before the screen exists. **This is not
implemented.**

### The "Sign" hand-off screen

The screen that opens the desktop application, and the only one with a clock
on it:

1. **"Opening SignFlow…"** with the document name, immediately on arrival.
2. The launch code is **valid for two minutes** (Phase B), so the screen says
   so and shows the time remaining rather than failing silently.
3. **Retry** as the primary action once it has expired — a fresh code, not a
   replay of the old one.
4. **Install fallback** as a text button throughout: *"Nothing happened?
   Install SignFlow"*, which is the existing download link.

The browser never becomes the signing environment: the certificate stays on
the smart card and the desktop application does the signing. The hand-off
screen's job is to say that clearly enough that nobody looks for an upload
button.
