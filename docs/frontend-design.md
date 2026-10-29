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

## 0. What it is built from

**Tailwind CSS v4 and shadcn/ui** (Radix primitives, `lucide-react` icons),
inside the existing Next.js static export. Tailwind v4 has no
`tailwind.config.js`: the theme is declared in CSS, in `web/app/globals.css`,
and `@tailwindcss/postcss` is the whole build setup
(`web/postcss.config.mjs`).

shadcn components are **copied into the repository** — `web/components/ui/*`
are ours to edit, and four of them are edited (§3).

### The dependencies, and three that were removed

| Package | Why |
|---|---|
| `clsx` + `tailwind-merge` | the `cn` helper in `lib/utils.ts` |
| `class-variance-authority` | the component variants |
| `radix-ui` | the primitives under dialog, menu, tabs, select… |
| `lucide-react` | icons, inline SVG, nothing fetched |
| `tw-animate-css` | the dialog/sheet enter-and-exit animations |
| `sonner` | toasts (component present; nothing raises one yet) |
| `@fontsource-variable/inter` | the self-hosted font |

- **`cn` removed.** `shadcn init` wrote `lib/utils.ts` as a one-line
  re-export from its own `cn` package — shadcn's, MIT, zero dependencies, no
  install hooks, a 612-byte wrapper around its bundled clsx + tailwind-merge
  engine. Nothing wrong with it; it is not what the ecosystem is written
  against, so `lib/utils.ts` is the standard `twMerge(clsx(inputs))` and the
  package is gone. Note that **`npx shadcn add` reinstalls it** whenever it
  generates a component, and rewrites the import — check after adding one.
- **`shadcn` (the CLI) removed from dependencies**, where `init` had put it
  at *runtime*. It was also the transitive source of a second `cn`. Use
  `npx shadcn@latest add <component>`; its `tailwind.css` is vendored (below).
- **`next-themes` removed.** `sonner.tsx` asked it which theme was active;
  this site is light only, so the answer is pinned and the dependency is not
  installed to answer a question with one possible answer.

`@import "shadcn/tailwind.css"` is **vendored** into `globals.css`: 7 of its 9
custom variants, by count of real uses (`data-open` 32, `data-closed` 30,
`data-checked` 12, `data-active` 11, `data-disabled` 8, `data-horizontal` 3,
`data-vertical` 2). Its `no-scrollbar`, `scroll-fade` and `shimmer` utilities
and accordion keyframes are 0 uses and are not copied. If a newly added
component uses `data-unchecked` or `data-selected`, Tailwind emits nothing and
fails silently — copy that variant across.

Under the Content-Security-Policy this website is served with — `default-src
'none'`, `script-src 'self'` plus hashes, `style-src 'self' 'unsafe-inline'`,
`img-src 'self'`, `font-src 'self'`, `connect-src 'self'` — none of this needs
anything relaxed. Utilities compile to one stylesheet; Radix sets inline
*styles*, which `style-src 'unsafe-inline'` already allows; lucide icons are
inline `<svg>`, so no image is fetched at all.

## 1. Tokens

CSS custom properties on `:root` in `web/app/globals.css`, in the shape
shadcn expects so every generated component picks them up. **Light only**,
deliberately: a dark theme means contrast decisions nobody has checked.
`color-scheme` is pinned to `light` so form controls follow.

**No hex and no raw palette class (`text-blue-600`, `bg-gray-100`) exists
outside this file.** One exception, and it is forced: `<meta
name="theme-color">` in `layout.tsx`, because a browser paints its own chrome
from that value before any stylesheet is parsed and so cannot read a
variable.

| Token | Value | Contrast | Used for |
|---|---|---|---|
| `--foreground` | `#0F172A` | **17.85:1** on white, 17.06 on muted | body text, headings |
| `--muted-foreground` | `#475569` | **7.58:1** on white, 7.24 on muted | supporting text, hints |
| `--primary` | `#1D4ED8` | **6.70:1** both ways with white | the one action per screen, links, icons |
| `--primary-hover` | `#1E40AF` | 8.72:1 with white | hover and active |
| `--ring` | `#3B82F6` | — | focus ring |
| `--background`, `--card`, `--popover` | `#FFFFFF` | — | the page and its surfaces |
| `--muted`, `--secondary` | `#F8FAFC` | — | quiet surfaces, the footer, table heads |
| `--accent` | `#EFF6FF` | — | the blue tint: selected cards, icon circles, badges |
| `--accent-foreground` | `#1D4ED8` | 6.16:1 on the tint | text and icons on the tint |
| `--border`, `--input` | `#E2E8F0` | — | every border |
| `--destructive` | `#B91C1C` | **6.47:1** on white, 5.45 on its tint | refusals, cancelling |
| `--success` | `#15803D` | 5.02:1 on white | solid fills, icons |
| `--success-strong` | `#166534` | **6.23:1** on a 10 % success tint | text on a success tint |
| `--warning` | `#B45309` | 5.02:1 on white | solid fills, icons |
| `--warning-strong` | `#92400E` | **6.20:1** on a 10 % warning tint | text on a warning tint |

**Why `-strong` exists.** `#15803D` and `#B45309` are AA on white, but a
status badge is text on *its own 10 % tint*, and there they measure 4.38 and
4.39 — under 4.5. One step darker of the same hue clears it. The badge keeps
the specified colour for its background and uses the `-strong` step for its
text, so the palette is the one that was asked for and every pair still
passes. Every number above was computed, not estimated.

**Radius** `0.75rem` (`--radius`); shadcn derives `sm`/`md`/`xl` from it.
**Shadow** `shadow-sm` on cards, `shadow-md` on popovers and dialogs, nothing
else. **Spacing** is Tailwind's 4 px rhythm — `gap-2/3/5/6`, `py-8 sm:py-10`
for a page, `space-y-5` inside a card. **Containers**: `.container-page`
(`max-w-6xl px-4 sm:px-6`) for lists and wide screens, `.container-narrow`
(`max-w-xl`) for one form or one document. One gradient exists, `.band-soft`,
behind the home hero and the account screens, fading to the page colour so it
has no visible edge.

## 2. Type

Inter, **self-hosted** (`font-src 'self'`; no font service is reachable under
the policy, by design), from `@fontsource-variable/inter` (OFL-1.1), emitted
under `/_next/static/media`. All seven subsets are kept, because the
dashboard shows the names of the people you sign with and those are not
always Latin.

| Step | Size | Weight | Used for |
|---|---|---|---|
| display | 30→48 px (`text-3xl sm:text-5xl`) | 600 | the home headline only |
| h1 | 24→30 px (`text-2xl sm:text-3xl`) | 600 | one per screen, via `PageHeader` |
| h2 | 18 px (`text-lg`) | 600 | card titles, section headings |
| h3 | 16 px (`text-base`) | 600 | a row's subject |
| body | **16 px** (`text-base`) | 400 | everything a reader reads |
| small | 14 px (`text-sm`) | 400 | hints, timestamps, meta |

**16 px is the floor for anything a reader reads, inputs included**: below
that, iOS Safari zooms when a field is focused, which on a form feels like
the page jumping away. The preset shipped `md:text-sm` on inputs and the
textarea; both were removed.

## 3. Components

From shadcn: `alert`, `alert-dialog`, `avatar`, `badge`, `breadcrumb`,
`button`, `card`, `checkbox`, `dialog`, `dropdown-menu`, `input`, `label`,
`progress`, `radio-group`, `select`, `separator`, `sheet`, `skeleton`,
`sonner`, `table`, `tabs`, `textarea`, `tooltip`.

Four are edited, and the edits are the accessibility ones:

| Component | Change | Why |
|---|---|---|
| `button` | `default` 32 → **44 px**, `lg` → 48, `icon` → 44, label 16 px | 44 px is the smallest touch target that stops being a guess |
| `button` | `hover:bg-primary/80` → `hover:bg-primary-hover` | fading on white *lightens*; a hover should darken |
| `input` | 32 → **44 px**, `md:text-sm` removed | same target size; 16 px stops the iOS zoom |
| `select`, `textarea` | trigger 32 → 44 px, textarea `min-h-24`, `md:text-sm` removed | same |

> `npx shadcn add` pulls in a component's dependencies and **overwrites them**
> — adding `sheet` and `alert-dialog` rewrote `button.tsx` and reverted the
> two edits above. Check `button.tsx` after any `add`.

### The shared blocks — `web/components/`

- **`blocks.tsx`** — `PageHeader` (title, one line, primary action right on a
  wide screen and underneath on a phone), `EmptyState` (icon, title,
  sentence, one button), `SectionCard` (the one card shape every screen
  uses), `ConfirmDialog` (an `AlertDialog`, used for both cancelling a
  document and withdrawing an invitation), `Initials` (an `Avatar` fallback
  from an address — no image is ever loaded; there is nowhere to load one
  from under `img-src 'self'`), `Links` and `TextLink` (`Button variant=link`
  + `next/link`).
- **`chrome.tsx`** — `Brand`, `SiteHeader` (signed out), `AppHeader` (signed
  in: Documents, **Send a document**, the account dropdown), a `Sheet` menu
  under `sm`, `SiteFooter`, and `AppShell`, the bar-content-footer shape of
  every signed-in screen.
- **`session.tsx`** — `useSession` (the server is the authority; the cookie is
  HttpOnly and unreadable here), `SessionPending` (a skeleton of the page, so
  the layout does not jump), `useNote`/`NoteAlert` (one `role="status"` region
  per screen, three variants each with its own icon), `Field` (label
  **above**, `hint` and `error` both wired through `aria-describedby`, and
  `aria-invalid` when there is an error), `Busy` (the in-button spinner).
- **`status.tsx`** — `StatusBadge` and `SignerBadge`: state in **words,
  colour and an icon**. The words come from `STATE_WORDS` in
  `lib/dashboard.ts`, which stays the one place a server state becomes
  English; these add only the icon and the tone.
- **`auth-shell.tsx`** — the logo and centred card on the soft blue band that
  every account screen sits in.

**Every link and control in `app/` is a shadcn component**, with three
deliberate exceptions, each a plain `<a>` inside `Button asChild`: the two
`download` links (the browser must fetch them itself, with the session
cookie — `next/link` would route instead) and the configured installer URL on
`/open`. The skip link in `layout.tsx` is the fourth, and is meant to be a
bare anchor.

## 4. The simplicity rules

1. **One main action per screen**, as one blue button. Everything else is an
   outline button or a plain link.
2. **Plain words.** "Document", "signer", "your card", "Sign now". Not
   "workflow", "slot", "launch code", "certificate bound".
3. **Labels above fields, always.** A placeholder is not a label: it
   disappears exactly when somebody starts typing.
4. **Errors say what happened and what to do** — next to the field through
   `Field`'s `error`, or in an `Alert` — in the server's own words where the
   server said anything. `429` becomes *"Too many attempts — please wait a
   few minutes and try again."* on every screen that can provoke one.
5. **Status is never colour alone**: words plus colour plus an icon.
6. **Touch targets ≥ 44 px**, body text ≥ 16 px, a visible focus ring on
   everything, and no horizontal scroll at 375 px.
7. **Busy states disable the button and say what is happening** — "Sending…"
   with a spinner — so nothing can be submitted twice.
8. **Empty states say what to do next**, not just that there is nothing.
9. **Motion is decoration**: `tw-animate-css` on dialogs and sheets, hover
   transitions at 200 ms, and a `prefers-reduced-motion` block in
   `globals.css` that reduces every animation and transition to nothing.

**Refusals never reveal whether an account exists.** A design constraint
rather than only a server one: sign-up, resend and reset-request answer `202`
whether or not anything happened, so the screens say *"If that address…"* and
mean it. One message covers a wrong password, an unknown account and a locked
one. The temptation in UI work is to be more helpful than that; being more
helpful here would undo the server's work.

## 4a. Accessibility

- **WCAG AA on every pairing in §1**, measured rather than assumed — the
  lowest is 5.45:1, and that is a badge on its own tint.
- **Visible focus** — a ring with an offset on buttons, inputs, links and
  menu items. Nothing removes an outline.
- **Keyboard** — real `<button>` and `<a>` underneath every component, labels
  bound with `for`/`id`, Enter submits because every form is a real `<form>`,
  and the Radix menu, tabs, radio group, dialog and sheet bring their own
  focus trapping and arrow-key handling.
- **The skip link** is the first focusable thing on every page and becomes
  visible when focused.
- **375 px** — one column, 16 px gutters. The dashboard switches from a table
  to cards rather than scrolling sideways, and the header's links move into a
  Sheet.
- Icons are `aria-hidden`; nothing depends on one to be understood.
## 5. Screens that exist

| Screen | Path | Notes |
|---|---|---|
| **The public page** | `/` | headline, three steps, two buttons |
| **Documents** | `/documents` | tabs: To sign (count) · Sent by me · Shared with me |
| **One document** | `/document?id=…` | state, signers, the main action, and the history behind a disclosure |
| **Send a document** | `/send` | a three-step wizard; the only page that uploads |
| **Open in the app** | `/open?id=…` | the hand-off, with the two-minute clock |
| Sign in | `/sign-in` | one message for every kind of refusal |
| Create an account | `/sign-up` | 202 either way; the confirmation page is worded for both |
| Forgotten password | `/forgot-password` | same |
| Confirm address | `/verify-email?token=…` | acts on arrival, then reports |
| Set a new password | `/reset-password?token=…` | refuses an absent token before asking for anything |
| My account | `/account` | two `SectionCard`s and a state badge; signing out is in the header menu |
| Change password | `/change-password` | its own screen; falls back to sign-in without a session |

Every path is one line in `FrontendRoutingConfiguration.PAGES`, which
`SecurityConfig.PUBLIC` reads as well, so a page cannot be routable but
refused — or permitted and never routed.

## 5a. The dashboard, as built

> **Redesigned.** The three stacked groups below are now **tabs**: *To sign*
> (with a count badge), *Sent by me*, and *Shared with me*. The third is not
> in the brief and appears only when it has something in it — without it, a
> document you are a signer of but did not send, and have already signed,
> belongs to neither of the other two and would vanish from the website while
> other people were still signing it. The grouping logic in
> `lib/dashboard.ts` is untouched; *To sign* is its `mine`.
>
> **Two things a row cannot show**, because `Row` does not carry them and
> `lib/*` was not to be changed: a **date**, and **who is next by name**.
> `mergeRows` keeps neither (`Workflow` has no timestamp at all, and the
> current signer's display name is in neither list). Adding them is a change
> to `lib/dashboard.ts` and its tests — worth doing, and not done here.

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

> **Redesigned into three steps** — *Choose the PDF* (with a drag-and-drop
> area), *Who needs to sign?*, *Review and send* — with a progress bar and a
> step list above the card, and Back/Next between them. Nine fields at once
> became one question at a time; what is sent, and every check in
> `lib/send.ts`, is unchanged. Step 1 validates with a stand-in signer row so
> it does not complain about a question the reader has not reached.
>
> **No name box per signer**, and that is the API rather than an omission: a
> signer is an email address (which is invited) or an existing username. A
> name field would be collected and thrown away. The brief asked for "name +
> email rows"; this is the one part of it that cannot be built without a
> server change.

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

## 5d. The hand-off, as built (Phase F)

> **Restyled only.** One centred card — *"Opening the SignFlow app… Insert
> your smart card"*, the document's name, the countdown in tabular figures,
> *"Didn't open? Try again"*, and the install fallback under a rule. Every
> line of the logic is untouched, and deliberately: the code still never
> enters the DOM (`location.href`, held in a ref), a code is never reused,
> and the clock is still recomputed from the issue time.

The screen that opens the desktop application, and the only one in this
website with a clock on it. All four points of the plan are there: *"Opening
SignFlow…"* with the document name on arrival, the two-minute validity shown
as a countdown, a fresh link rather than a replay when it runs out, and an
install fallback on every state of the screen. What changed in the building
is recorded below.

**The path is `/open?id=…`, not `/sign`** — although the button says *Sign it
in SignFlow*. `/sign/{token}` is already the public, server-rendered
invitation page, and a second meaning one segment from it is the kind of
neighbourhood where a matcher written `/sign*` covers a page nobody meant it
to. `/open` also describes what the screen does.

**The code is never in the page.** The server answers with a deep link
carrying a single-use code; the page navigates to it by assigning
`location.href` and keeps it in a ref, never in rendered state. An
`<a href={deepLink}>` would have been the easy way and puts a credential in
the DOM for anyone to copy out of a right-click menu.

**It tries once, then offers a button.** Navigating to a custom scheme is
privileged: browsers may ignore it when no click caused it, and some ask the
reader to confirm. So the page attempts the hand-off on arrival — the reader
did click something to get here — and leaves *"Open SignFlow again"* as the
primary action rather than sitting on "Opening…" if the attempt was refused.

**The clock is read, not counted.** Every tick recomputes from the issue time
rather than decrementing a number in state, so a tab that was asleep for a
minute comes back with the right answer instead of a stale one. Tabular
figures, so the digits do not jitter as they fall.

**Retry is offered only where a retry could work.** `TOO_MANY_REQUESTS` (five
codes a minute) and a network failure are worth another go. An unregistered
card, somebody else's turn, a finished document: those are states, and a
button that cannot change them is worse than no button. The refusal itself is
the server's own sentence, as everywhere else.

**The install fallback is a link only when the deployment has one.** It comes
from `GET /web/api/app-info`, which serves the same `SIGNER_DOWNLOAD_URL` the
invitation page has always rendered. It is unset by default — this project
builds an installer with `scripts/package.sh` and hosts it nowhere — so the
screen says *"SignFlow has to be installed on this computer to sign"* instead
of offering a link that 404s. Inventing a download URL would have been
inventing a fact.

**What the first-time signer still has to do elsewhere.** A launch never
enrols a certificate (Phase B review, H2), so the first time a person signs,
their card has to be registered from the desktop application while signed in
there — the server refuses the launch with `CERTIFICATE_NOT_BOUND` and says
exactly that. Every workflow this website creates starts unbound, so this is
the common path, not the edge case. It is why decision D5 keeps the desktop's
own sign-in until the website is in production, and it is the next thing to
solve in Phase F rather than something this screen can work around.

## 6. Planned, not built

The rest of Phase F: registering the `signflow://` scheme in the installer so
an installed copy can receive these links at all (audit finding F2 — the
packaged build registers nothing today), and then retiring the desktop's own
sign-in, workflow wizard and start-up gate. Both are desktop and installer
work rather than screens, and the second is explicitly gated on the website
equivalents being verified in production first (decision D5).
