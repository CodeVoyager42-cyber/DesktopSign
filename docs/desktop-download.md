# Publishing the desktop application

How a release of the SignFlow desktop app is built, published and installed —
and what each of those steps deliberately does not do.

The website cannot sign anything: the certificate lives on a smart card and
the desktop application is what reaches it. So the download is not a
convenience, it is the second half of the product, and finding it must not
require an account — somebody who has not signed up yet still has to be able
to get the application.

---

## 1. Build a release

```bash
scripts/package.sh                # builds the jar, then packages
scripts/package.sh --skip-build   # reuse an existing target/*.jar
```

Produces, in `build/dist/`:

| File | What it is |
|---|---|
| `SignFlow-<version>.msi` | **the deliverable** — a per-user installer |
| `SignFlow-<version>.msi.sha256` | its checksum, as `sha256sum` writes one |
| `SignFlow-<version>-windows.zip` | a portable build for anyone who cannot run an installer |
| `SignFlow-<version>-windows.zip.sha256` | its checksum |

Requires a JDK with `jpackage` (21+) and **WiX 3.x** for the MSI. Without WiX
the script says so and stops at the zip — which is a worse deliverable,
because it registers nothing (§3).

### The MSI is not code-signed

**Windows SmartScreen will warn about it**, and the reader has to choose
*More info → Run anyway*. That is not a bug to be worked around; it is what
Windows says about every installer that is not signed by a certificate it
recognises. Working around it is not possible and should not be attempted —
the honest fix is a code-signing certificate, at which point `package.sh`
gains a `signtool` step and this section is deleted. Until then the website
says so plainly on `/download`, and publishes the checksum so a reader who
wants to verify what they downloaded can.

## 2. Publish it

There are now two ways a release reaches a deployment's download folder.

### 2a. Automatically, from GitHub (the mirror)

When the server has a GitHub token, the latest release of the source
repository is published **on demand**: the first request for a file the
folder does not hold — `GET /downloads/SignFlow-0.2.1.msi`, say — fetches the
matching release asset from GitHub, verifies its bytes against the SHA-256
digest GitHub publishes for each asset, and only then moves it into
`SIGNER_DOWNLOAD_DIR`. From then on the file is served like any other, and
the checksum `.sha256` beside it is written out of the verified digest.

```bat
set GITHUB_API_TOKEN=github_pat_...
```

The token needs only **read access to the repository** — a fine-grained
personal access token with *Contents: Read* is enough, and it is read from
the environment, never from a file in the repository. Without a token the
whole feature is off and §2b is the only way. The other settings are
env-overridable but rarely need it:

| Variable | Default | What it is |
|---|---|---|
| `GITHUB_API_TOKEN` | *(unset)* | fine-grained PAT with Contents: Read; unset disables mirroring |
| `GITHUB_RELEASE_REPOSITORY` | `CodeVoyager42-cyber/DesktopSign` | the source repository |
| `GITHUB_API_BASE_URL` | `https://api.github.com` | where the API lives (tests, proxies) |
| `GITHUB_RELEASE_CACHE_TTL` | `PT1H` | how long release metadata is remembered |

The deliberate properties of the mirror:

- **At most one GitHub request an hour**, however many people read the
  pages — release metadata is cached in the server's heap for
  `GITHUB_RELEASE_CACHE_TTL`. A failing GitHub is shielded further by a
  60-second cooldown, and the last known release keeps being served while
  the source is down.
- **Existing files win.** A file the folder already holds is never
  re-fetched or overwritten; the mirror only fills gaps. An operator who
  copied a release in themselves (§2b) keeps their copy.
- **Nothing unverifiable is published.** An asset without a well-formed
  `digest` is never mirrored; bytes that fail the digest or the declared
  size never reach the folder (they are streamed to a `.part-` scratch file
  the allow-list cannot serve, verified, then moved in).
- **A HEAD request never triggers a fetch** — an uptime monitor asking
  whether the installer exists is not asking for it to be created.
- The token never leaves the server process: it goes to `api.github.com`
  only, and is stripped before following GitHub's redirect to the
  user-content host that serves the bytes.

The dashboard's download section reads the newest release from
`GET /web/api/latest-release` (behind the session, like the rest of
`/web/api`), so once a release is published on GitHub it appears on the
dashboard without any operator action.

### 2b. Manually, as before

```bash
# Wherever SIGNER_DOWNLOAD_DIR points — OUTSIDE the jar and the repository
copy build\dist\SignFlow-0.1.0.msi          D:\signflow-downloads\
copy build\dist\SignFlow-0.1.0.msi.sha256   D:\signflow-downloads\
copy build\dist\SignFlow-0.1.0-windows.zip  D:\signflow-downloads\   # optional
```

Then restart the server — or do not: the directory is read on each request,
so a file copied in is offered immediately and a file removed stops being
offered. The restart in the original instructions is unnecessary and the test
`theDirectoryIsReadLazilySoAReleaseCanBePublishedWithoutARestart` is what
keeps it that way.

```
SIGNER_DOWNLOAD_DIR=D:\signflow-downloads
# Optional: what the signed-in dashboard button saves the file as.
SIGNER_INSTALLER_FILENAME=SignFlow-Setup.msi
```

The directory must be outside the jar **and outside anything the server
publishes as static content** — nothing under `src/main/resources/static`,
because that directory *is* the website. Files are read out of it by name and
the path itself never reaches a browser: it is not in a URL, not in an error
message and not in a response header.

`SIGNER_INSTALLER_FILENAME` may contain letters, digits, dot, dash and
underscore only, and must end in `.msi`. It is written into a
`Content-Disposition` header, and restricting its shape is how nothing can be
injected into one; anything else **fails startup** rather than being escaped.
It does not affect `/downloads/**`, which keeps each file's published name.

**Set but missing or unreadable fails startup**, with a sentence saying which
of the two it is. A deployment that names a directory and gets it wrong should
hear about it at once, rather than serving 404s that read like "no release
published yet".

Unset is supported: the website then offers `SIGNER_DOWNLOAD_URL` if that is
configured, and
otherwise says the download is not available yet.

### What is served, and what is refused

There are **two routes to the same bytes**, and the difference between them
is who may ask, not what they get:

| Route | Sign-in | Saved as | For |
| --- | --- | --- | --- |
| `GET /downloads/{fileName}` | none | the published name, e.g. `SignFlow-0.1.0.msi` | anyone who has not got an account yet |
| `GET /web/api/desktop/installer` | **session required** | `SIGNER_INSTALLER_FILENAME`, default `SignFlow-Setup.msi` | the signed-in dashboard button |

The public one is public deliberately: somebody evaluating SignFlow cannot be
asked to create an account before installing the application that does the
signing. The signed-in one is what the dashboard's **Download for
Windows** button uses, so a reader who *does* have an account is served
through the session they already have, under a stable name that does not
change from release to release.

The signed-in route has no authorisation code of its own. It is protected by
being under `/web/api/**`, which `WebSecurityConfig` ends with
`hasAuthority(ROLE_WEB_USER)` — so an anonymous `GET` *or* `HEAD` of it is
`401` before any controller runs, and a route added under that prefix cannot
be forgotten about. `WebInstallerDownloadHttpTests` asserts the `401`, the
`200`, and that the public route is still open.

Both are rate limited per address together (60 requests a minute by default,
shared with `/web/api/download-info`), so the two cannot be alternated to buy
twice the requests.

Neither loads the file into memory: the body is a `FileSystemResource`, which
the container streams, and `Range` is answered with a `206`, so a large
installer on a bad connection resumes rather than restarting.

A name is served only if it matches one of
`SignFlow-<version>.msi`, `SignFlow-<version>-windows.zip`, or either with
`.sha256` appended — and then only if it resolves to a regular file whose
real path, with symbolic links followed, is directly inside the configured
directory. Everything else is `404`, always the same `404`: not `403` (which
would confirm a file exists), not `500`, and never a message naming a path on
the server.

## 3. What the installer does that the zip does not

It registers the `signflow://` URL scheme, pointing at the installed
`SignFlow.exe` with the URL as a quoted argument. That is what makes the
website's **Sign** button able to open the app at one document (security
Phase F; audit finding **F2** — the packaged build used to register nothing,
so an installed-only machine could not receive a hand-off at all).

The registration is written **by the installer**, in
`packaging/windows/main.wxs`, and not by the application at first run:

- the installer knows the final path of the executable and writes it once;
- the **uninstaller removes it**, because the keys are a component of the
  product — an app that writes the registry at startup has no equivalent
  moment to clean up, and would leave a scheme handler pointing at a deleted
  executable;
- a per-user install writes under `HKCU`, which the user already owns, so
  nothing needs elevation.

`scripts/register-signflow-protocol.cmd` stays for development, where there
is no installer.

**A maintenance cost comes with that choice.** jpackage's `overrides.wxi`
hook can only set WiX variables, so `main.wxs` is a full replacement of the
JDK's template and has to track it. `packaging/windows/main.wxs.jdk-base` is
the pristine copy from the JDK it was written against (26.0.2), and
`package.sh` diffs the JDK in use against it at build time and warns loudly
if they differ. Our file is that base plus 87 lines and minus nothing.

## 4. Pointing SignFlow at your card

SignFlow signs through **PKCS#11**: it talks to the driver your card's issuer
supplies, and does not replace it. Install that driver first, then tell
SignFlow where it is:

```bat
set PKCS11_LIBRARY=C:\Windows\System32\opensc-pkcs11.dll
```

Common middleware, for reference:

| Middleware | Library |
|---|---|
| OpenSC | `opensc-pkcs11.dll` |
| Thales / Gemalto | `gclib.dll` |
| AWP (Atos / Bull) | `aetpkss1.dll` |
| SafeNet eToken | `eTPKCS11.dll` |

They are usually in `C:\Windows\System32`. If the app cannot find a card,
that variable and the reader being plugged in are the first two things to
check.

> A first-run settings screen that scanned those paths and let the reader
> pick one would be friendlier, and is **not built**: it is desktop UI work
> with its own detection, persistence and tests, and it was deliberately left
> out of the download feature rather than bolted onto it.

## 5. What the installed app runs

`signflow.packaged=true`, set as a launcher option by `package.sh`. That is
what `ProductionSafetyValidator` reads to refuse the `dev` profile — the mock
token, the default accounts and plain HTTP — so an installed copy cannot be
talked into development behaviour. The launcher also pins
`-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`, so the coordination server's
TLS certificate is trusted through the Windows certificate store
(`docs/TRANSPORT-SECURITY.md`).

## 6. The page a reader sees

`/download` — public, linked from the header, the footer, the mobile menu,
the hand-off screen's fallback, and the sign-up confirmation. It shows the
version and size, the three install steps, what the machine needs, the
SmartScreen warning in plain words, the checksum with a copy button and the
PowerShell command to compare it, and a troubleshooting section. When nothing
is published it says so and points at the administrator, which is the truth
rather than a broken button.

The signed-in dashboard carries the same offer as a **"Get the SignFlow
app"** card: the newest release fetched live from
`GET /web/api/latest-release`, the MSI as the prominent button and the
portable zip beside it, the version line, the same checksum verification and
smartcard guidance folded away, and a link to `/download` for everything
else. It replaced the old "you need the app" alert, which could only point
at a page instead of at the file.

### The dashboard button, and why it makes two requests

`InstallerButton` (`web/components/installer-button.tsx`) does not fetch the
installer into the page. It hands the URL to the browser, because that is the
only way a 200 MB file never becomes a blob in memory — and it is the browser
that then shows progress, offers a resume and saves the file.

A navigation cannot be taken back, though, so the status is asked for first
with a `HEAD` of the very route that will serve the file, and only a `200`
starts the download:

| Preflight | What the reader sees |
| --- | --- |
| `200` | the download starts; the dashboard stays where it is |
| `401` | sent to `/sign-in`, the same way `useSession` sends them |
| `404` | "No installer has been published yet…" |
| `429` | "Too many download attempts from this network…" |
| anything else, or unreachable | one generic line, and the button comes back |

A `HEAD` never mirrors from GitHub, for the reason `/downloads/**` does not
either: fetching 200 MB to answer a preflight would make the button feel
broken. When the folder is empty but the latest release *does* publish an
installer, the `HEAD` answers `200` on that basis and the `GET` is what
actually mirrors.

Double clicks cannot send two requests: a ref guards the handler (state is
asynchronous, so a second click can arrive before React has re-rendered) and
the button is disabled while the preflight is in flight. The ref is the
correctness; the disabled attribute is what the reader sees.
