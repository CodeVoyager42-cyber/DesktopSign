# Publishing the desktop application

How a release of the SignFlow desktop app is built, published and installed —
and what each of those steps deliberately does not do.

The website cannot sign anything: the certificate lives on a smart card and
the desktop application is what reaches it. So the download is not a
convenience, it is the second half of the product, and finding it must not
require an account — somebody invited to sign has no account yet.

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
| `release-manifest.json` | build provenance: version, commit, build time, every artefact with its checksum and size |

Requires a JDK with `jpackage` (21+) and **WiX 3.x** for the MSI. Without WiX
the script says so and stops at the zip — which is a worse deliverable,
because it registers nothing (§3).

### Why per-user, not per-machine

jpackage builds a per-machine MSI by default: it installs into `Program
Files` and needs administrator rights. That was tried and rejected. A silent
per-machine install cannot show the UAC prompt it depends on, so it fails
with *"forbidden by system policy"* (error 1625) — and the failed attempt
left **256 orphaned files** under `Program Files`, because Windows
Installer's own rollback also needed elevation it did not have.

`--win-per-user-install` installs into the user's own profile instead. It
needs no elevation at any step — install, launch and uninstall each complete
from a non-elevated shell — and it still gets a Start Menu entry
(`--win-menu`, which was missing at first: without it the MSI installed with
no way to launch what it had just installed). The trade-off is that a machine
administrator does not see it under *installed for all users*, which does not
matter for how this is distributed: an individual signer downloads it, IT
does not push it.

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
```

**Set but missing or unreadable fails startup**, with a sentence saying which
of the two it is. A deployment that names a directory and gets it wrong should
hear about it at once, rather than serving 404s that read like "no release
published yet".

Unset is supported: the website then offers `SIGNER_DOWNLOAD_URL` if that is
configured (the link the invitation page has used since Phase 3), and
otherwise says the download is not available yet.

### What is served, and what is refused

`GET /downloads/{fileName}` — public, no sign-in, rate limited per address
(60 requests a minute by default, shared with `/web/api/download-info`).

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

### What a `signflow://` link may contain

`signflow://invitation/<token>` — **the token is the entire payload**, and it
is validated in `InvitationLinks` against `[A-Za-z0-9_-]{32,}` before
anything else looks at it; a link that does not match is treated as absent
rather than partially parsed. The token is opaque, single-use, server-issued
and time-limited. It is never a file path, never a credential, and never
something that grants access by itself: the app redeems it against the
coordination server (`GET /api/invitations/{token}`, which is where expiry,
revocation and already-used are decided). A link is never trusted because of
the scheme it arrived on. Malformed, expired, revoked and already-used tokens
all end at a readable notice, never a raw exception.

### When a copy is already running

`SingleInstanceGuard` keeps one SignFlow running at a time and hands a second
launch's link to the copy already up. The channel is deliberately **not a
loopback port**: binding `127.0.0.1` authenticates nobody, so any local
process could have received a forwarded token (security Phase B, finding
**F1**). It is a **Unix-domain socket** in a per-user run directory
(`%LOCALAPPDATA%\SignFlow\run`, or `~/.signflow/run`) whose owner and
permissions are set and then read back and checked, rather than assumed.

Both ends prove they know a per-run secret before anything is sent: the
second copy makes the running copy answer an HMAC challenge over a fresh
nonce and sends nothing until that proof checks out, and its own message is
signed over the running copy's nonce in turn — so neither forgery nor replay
works. If any of that cannot be established the design **fails closed**:
nothing is forwarded, no listener starts, and the app either runs on its own
or says SignFlow is already running. A copy that is starting or busy is
waited for (8 seconds) and then reported, never killed — it may be in the
middle of signing.

**This is not server mode.** Desktop mode's Spring context is
`WebApplicationType.NONE` always; the guard is a socket opened for this one
purpose, not a side effect of enabling the `server` profile.

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


### Testing against the development server

There is **no flag that unlocks the `dev` profile in a packaged build**, and
adding one is not the way to test an installed build. An override here ships
inside the installer, where an environment variable set once — or inherited
from a parent process — is enough to turn a real signing identity into the
mock one. A revision that honoured `ALLOW_DEV_PROFILE=true` did reach the
released v0.2.1; it has been removed, and
`ProductionSafetyValidatorTests.noOverrideLetsAPackagedBuildRunDevMode`
keeps it removed.

To exercise the development server, run SignFlow **unpackaged**, which is
where the `dev` profile belongs:

- `run-app.cmd` from a source checkout (the dev profile by default), or
- the portable app-image from the release zip, launched without
  `-Dsignflow.packaged=true`.

Both reach the same server with the same mock token, and neither needs the
installed copy to be weakened to do it.

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

## 7. Code signing

**Not done here**, and nothing in this repository claims otherwise: there is
no code-signing certificate. That is what the SmartScreen warning in §1 is,
and the first run of an unrecognised binary also costs a one-time antivirus
behavioural scan (around 40 seconds, observed — not a fault in the app).

With a certificate from a CA, signing is a step after `scripts/package.sh`
and before the checksums, because the signature changes the bytes being
hashed:

```powershell
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 `
    /a "build\jpackage\SignFlow\SignFlow.exe"
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 `
    /a "build\dist\SignFlow-<version>.msi"
```

Sign both: the MSI carries its own signature, separate from the executable
inside it. Then re-run the checksum step, so the `.sha256` files and
`release-manifest.json` describe the signed bytes.

## 8. What uninstalling removes

Uninstalling the per-user MSI (Settings → Apps, or `msiexec /x`) removes what
the installer placed under the user's profile **and the `signflow://` keys**,
because those keys are a component of the product (§3). Nothing is left
pointing at an executable that is no longer there.

It deliberately does not touch anything the installer did not create: the
documents that were signed, or `~/.signflow/` — the dev-only mock-token
keystore, which a packaged build never has in the first place.

## 9. Verified, and not verified

Exercised on a real Windows machine and confirmed by looking at process
state, logs or registry contents, rather than reasoned about:

- per-user MSI silent install (`msiexec /i ... /qn`) from a **non-elevated**
  shell, exit code 0; Start Menu shortcut present; silent uninstall likewise;
- `signflow://` resolving to the installed `SignFlow.exe` after an install,
  and the keys gone again after an uninstall;
- the already-running hand-off: a second `signflow://` launch while the first
  was up forwarded the token, log-confirmed;
- a missing or misconfigured PKCS#11 library stopping start-up with the
  intended dialog rather than crashing or hanging;
- desktop mode running with `WebApplicationType.NONE` — launching the app
  starts no HTTP server.

Not verified here, and not to be assumed:

- real PKCS#11 hardware — only the missing/misconfigured-library path was
  exercised, never a card that actually signs;
- code signing — no certificate available (§7);
- a genuinely separate second machine for the hand-off: two processes on one
  machine exercise the same lock and the same loopback path, but that is not
  the same test;
- upgrade in place — installing a newer MSI over an existing per-user
  install. Only clean install and uninstall were run.

## 10. Release checklist

1. `mvnw.cmd verify` — the suite green before anything is packaged.
2. `scripts/package.sh` — the MSI, the zip, a `.sha256` beside each, and
   `release-manifest.json`.
3. Optional, and only with your own certificate: sign the `.exe` and the
   `.msi` (§7), then re-run the checksum step so the hashes match the signed
   bytes.
4. Tag `vX.Y.Z` and push. `.github/workflows/release.yml` builds, tests,
   packages, and attaches the zip, the MSI, the checksums and the manifest to
   the GitHub Release.
5. Nothing to copy by hand afterwards: a deployment holding a GitHub token
   mirrors the new release on demand (§2a).
