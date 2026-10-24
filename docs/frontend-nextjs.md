# The website — Next.js, exported, served by the server

`web/` holds the website: Next.js 15 (App Router) and TypeScript, built to
plain files and served by the Spring Boot application from its own origin.
The visual system it renders is `frontend-design.md`; the security reasoning
behind same-origin, the session cookie and the refusal wording is
`security/phase-d-accounts.md` §7a–§7b.

**Not DocuSign.** The page structure follows the pattern that family of
products uses, because it is the order readers expect — hero, proof, a tab
filter over feature cards, how it works, the security case, closing call to
action. Nothing is borrowed from anyone's identity: no logo, no brand colour,
no copied copy, and no customer logos, metrics or testimonials, because
invented proof is a fabricated record whoever's name is on it. Where a
marketing page would put a customer-logo marquee, this one lists properties
of the system that can be checked.

## 1. What replaced what

| Before | Now |
|---|---|
| `static/index.html` + three hand-written modules | `web/`, built to `src/main/resources/static` |
| One document building every view in the browser | One real document per route |
| Tokens in `assets/app.css` | The same tokens in `web/app/globals.css` |
| Inter referenced but **absent**, falling back to the platform font | Inter shipped in the build, served from this origin |
| `script-src 'self'` | `script-src 'self'` **plus a hash per inline script** |

## 2. The decision: a static export, not a Next.js server

`next build` with `output: "export"` writes files; the jar serves them. The
alternative — running Next's own Node server behind a proxy — was considered
and rejected:

- The session cookie is `__Host-` prefixed, `HttpOnly`, `SameSite=Lax`, and
  the CSRF token is a double-submit cookie. **All of that depends on the page
  and the API being one origin.** A second server is same-origin only as long
  as a reverse proxy in front of both says so, and that is a deployment
  promise rather than a property of the application.
- It would add a second runtime to deploy, patch and monitor, for a frontend
  with no server-side work to do. The Spring application *is* the backend.

What the export gives up is server rendering, route handlers and middleware.
None of them are a loss here: there is nowhere else for server logic to live,
and putting any there would split the security model in two.

Consequence to know: **every page is a client component.** Data is fetched in
the browser, after the page has loaded, with the session cookie — which is
also how the hand-written version worked, since the server has always been the
authority on whether a session exists.

## 3. The CSP, and the one real cost of Next.js

The export emits inline `<script>` blocks on every page — the payload React
needs to take over the server-rendered markup
(`self.__next_f.push([...])`). There is no setting that moves them into a
file. `script-src 'self'` does not permit an inline script, so without
something they would load their code and then do nothing.

Three ways out, and they are not equal:

| | |
|---|---|
| `'unsafe-inline'` | Permits **every** inline script, including an injected one. That is the protection the policy exists for, traded for a build tool's convenience. **Rejected.** |
| A per-response nonce | The usual answer, and it needs the server to rewrite every page as it serves it. These are static files served by the resource handler, which is why they are cheap and cacheable. **Rejected.** |
| **Hashes** | Each permitted script named by the hash of its exact contents. A script that was not in the build does not run however it got into the page. **Chosen.** |

`security/InlineScriptHashes` reads the exported HTML **off the classpath at
startup** and hashes what it finds, rather than keeping a generated list
beside the policy. A list somebody has to regenerate is stale exactly once —
after a frontend change, when the pages quietly stop working.

The cost, stated plainly: about 50 bytes of header per distinct inline script
(29 of them today, ~1.6 KB), on every response. That is the price of not
weakening `script-src`, and it is the right side of that trade.

`FrontendServingHttpTests.everyInlineScriptIsPermittedByItsOwnHashAndNothingElseIs`
computes the hashes the way a browser does and asserts the header names every
one — so a build that emits a script the policy does not cover fails here
rather than in somebody's console. It also asserts the pages *have* inline
scripts, so that if a future Next.js stops emitting them, the test says so
instead of passing vacuously.

## 4. Building, and why the output is committed

```bash
cd web
npm install
npm run sync      # next build, then copy out/ into the server's resources
npm test          # the grouping logic, in Node's own test runner
npm run dev       # a dev server on :3000 — see the caveat below
```

`npm run sync` **wipes** `src/main/resources/static` before copying, so a page
that is no longer exported stops being served and no stale chunk lingers.

The export is **committed**. That is a real cost — generated, minified files
in version control, and a large diff whenever the frontend changes — accepted
for one reason: `mvn test` boots the server and asserts that it serves the
website, so the files have to be in the jar whether or not the machine
building it has Node. The alternative is making the Maven build run npm, which
puts a Node toolchain in front of every server build and every CI job for a
frontend that changes far less often than the server. If that trade ever
stops making sense, `frontend-maven-plugin` is the replacement and this
paragraph is the thing to delete.

**`npm run dev` caveat.** The dev server is useful for working on layout and
copy, but it is a different origin from the API, so the session cookie will
not travel to it and anything behind sign-in will not work. For that, run
`npm run sync` and restart the server. A dev-only rewrite proxy would fix it
and is deliberately not configured: `output: "export"` does not support
rewrites, so it would mean two config shapes and a dev build that differs
from the real one.

## 5. Routing

Each page is its own document, so `FrontendRoutingConfiguration` forwards a
path to its own file — `/sign-in` → `/sign-in.html` — derived from the path
rather than being one constant. `PAGES` is still the single list, and
`SecurityConfig.PUBLIC` still reads it, so a page cannot be routable but
refused, nor permitted and never routed.

Both the path **and** the file are public now. The forward is a second
dispatch, and whether it is filtered again depends on
`spring.security.filter.dispatcher-types` — a setting, not a guarantee. This
was found the direct way: every page answered 401 until the file was listed
too.

| Path | What it is |
|---|---|
| `/` | the public page: hero, features, how it works, security, CTA |
| `/sign-in`, `/sign-up`, `/forgot-password` | the account screens |
| `/verify-email?token=…`, `/reset-password?token=…` | the e-mailed links |
| `/documents` | the dashboard: action required, waiting for others, finished |
| `/account`, `/change-password` | the account screens behind a session |

`/_next/**` is the built code, stylesheets and fonts, each content-hashed by
the build. `/*.txt` is the per-page payload the router fetches when moving
between screens without a reload.

## 6. Fonts

Inter comes from `@fontsource-variable/inter` (OFL-1.1), **not** from
`next/font/google`. Both self-host, but `next/font/google` downloads the font
at build time, so the build needs the internet — which it does not have here,
and the first attempt failed on exactly that. The package ships the `.woff2`
files, so the build is offline and reproducible.

All seven subsets are emitted (≈220 KB in the repository, of which a browser
downloads only what a page needs, by `unicode-range`). Keeping Cyrillic,
Greek and Vietnamese is deliberate: the dashboard shows the names of the
people you are signing with, and those are not always Latin.

## 7. What is tested, and what is not

- **Served correctly** — `FrontendServingHttpTests` (15 tests): every listed
  page is its own document, the hashes cover the inline scripts, the assets
  and the font are served with types a browser accepts, HEAD works, a missing
  asset is a 404 and not a page, and nothing else became readable.
- **The grouping logic** — `web/lib/dashboard.test.ts` (7 tests) in Node's
  own runner: a document in both lists appears once, the viewer's own slot
  decides the group, cancelled and failed are finished.
- **Not tested here: that it renders and behaves in a browser.** Nothing in
  either suite executes React. The page structure, hydration and the
  interactions are checked by opening the site — `run-server.cmd`, then
  http://localhost:8080.
