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

**No `Open ›` link yet.** The plan gave every row one, but the screen it
would open is the status timeline (§6), which is not built — so the rows
carry what can be acted on today instead: a finished one offers *Download the
signed PDF ›*, and an action-required one says *"Open SignFlow on your
computer to sign this"* rather than a button that could not work. Signing
needs the certificate on the signer's own card; the hand-off that opens the
application is Phase F.

The download is a plain `<a download>` rather than a fetch: the browser
fetches it with the session cookie, same origin, and the bytes never pass
through the page's JavaScript. It is also what keeps the client-side router
from swallowing the click and trying to render a PDF as a page.

## 6. Planned, not built — Phases E and F

Recorded here so the shape is agreed before the screens exist. **None of
this is implemented.** The routes for the timeline do exist now (Phase E
parts 1–3); the "Sign" hand-off belongs to Phase F.

### Document status timeline

A vertical list of what happened, one row per event, each with a state dot in
accent (done), `--line` (pending) or `--warn` (blocked): *created · sent to N
· signed by X · waiting for Y · completed*. It is a read of the audit events
the server already records, which is why it can be honest about ordering
rather than inferring it.

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
