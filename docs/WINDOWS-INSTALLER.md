# Windows installer and distribution guide

How SignFlow is packaged for end users, what the `signflow://` custom
protocol does and doesn't trust, what's bundled vs. left to the user, and
how to actually ship a release. Everything here was built and verified
against a real Windows 10 machine — install, launch, uninstall, protocol
registration, and the failure paths were all exercised, not just reasoned
about. Where something couldn't be verified (real PKCS#11 hardware, a
genuinely separate machine), that's called out explicitly rather than
assumed to work.

## 1. What gets built, and from what

| Tool | Role |
|---|---|
| Maven (`./mvnw`) | Builds the self-contained Spring Boot fat jar |
| `jpackage` (bundled with JDK 21+) | Wraps that jar with a jlink'd JRE into a native Windows app |
| WiX Toolset 3.x (optional) | Lets `jpackage --type msi` produce an actual `.msi` installer |

No Inno Setup, no third-party installer framework — `jpackage` alone
covers everything needed (bundled runtime, Start Menu shortcut, clean
uninstall), so that's what's used. `package.sh` drives all of it in one
script; there is no separate PowerShell packaging script, because the
existing bash script already runs correctly on Windows under the Git Bash
shell `mvnw`/CI already depend on, and duplicating it in PowerShell would
be two scripts to keep in sync for no behavioral gain.

### Build it

```bash
./package.sh                # builds the jar, then packages
./package.sh --skip-build   # reuses an existing target/*.jar
```

Requires: JDK 21+ with `jpackage` on `PATH` or `JAVA_HOME`. WiX 3.x
(`choco install wixtoolset`, or `winget install WiXToolset.WiXToolset`) if
you want the `.msi`; without it you still get the portable zip.

### Output — all in `build/dist/`

| File | What it is |
|---|---|
| `SignFlow-<version>-windows.zip` | Portable: unzip anywhere, run `SignFlow.exe`. No install, bundled JRE. |
| `SignFlow-<version>.msi` | Per-user installer (`--win-per-user-install`) — **no admin rights needed**, Start Menu shortcut (`--win-menu`), clean uninstall via Windows "Apps" settings. |
| `SHA256SUMS.txt` | SHA-256 of each artifact, `sha256sum`-compatible. |
| `release-manifest.json` | `{name, version, architecture, platform, gitCommit, builtAt, artifacts[]}` — machine-readable build provenance. |

The raw, unzipped app-image also lands in `build/jpackage/SignFlow/` —
useful for quick local testing, not something to distribute (no installer
wrapper, no uninstall).

Both `build/` and `*.env` (except `.env.example`) are already gitignored;
nothing from a build or a local secrets file risks being committed.

### Why per-user, not per-machine

The MSI defaults to per-machine install in `jpackage` (install to
`Program Files`, needs admin/UAC). That was tried and rejected here: a
silent per-machine install can't show the UAC elevation prompt it needs,
fails with "forbidden by system policy" (error 1625), and on top of that
left 256 orphaned files under `Program Files` because Windows Installer's
rollback also needed elevation it didn't have. `--win-per-user-install`
installs to the user's own profile instead, needs no elevation at any
step (verified: install, launch, and uninstall all completed with exit
code 0 from a non-admin shell), and still gets a working Start Menu entry.
The tradeoff — a per-machine admin wouldn't see it in "Installed for all
users" — doesn't matter for this app's actual distribution model
(emailed/downloaded by the individual signer, not pushed by IT).

## 2. The `signflow://` custom protocol

### Registration

Packaged installs **self-register** on first launch — nobody runs a
script by hand. See `ProtocolRegistration.java`
(`src/main/java/com/example/signer/ui/ProtocolRegistration.java`):

- Only fires when `-Dsignflow.packaged=true` is set (baked into the
  jpackage build's `--java-options`) — dev launches (`mvnw javafx:run`,
  `run-app.cmd`) never touch the registry.
- Writes `HKCU\Software\Classes\signflow` — **current user only, no admin
  needed.** Points at `ProcessHandle.current().info().command()`, which
  for a jpackage app-image is the real native launcher path, so it
  self-heals after every reinstall, update, or relocation.
- Implemented via a generated `.reg` file imported with `reg import`, not
  `reg add /d "..."`. This was a real, verified bug: the
  `shell\open\command` value needs two separately-quoted tokens
  (`"<exe>" "%1"`), and `ProcessBuilder` cannot reliably rebuild that into
  a Windows command line — `reg.exe` rejects it with `Invalid syntax`
  every time. A `.reg` file carries the value as escaped text instead, no
  argv-quoting layer involved. Confirmed working end-to-end against a
  real per-user install. (If you ever hand-write a `.reg` file for this:
  it must be CRLF line endings and UTF-8 **without** a BOM — a plain-LF
  file silently no-ops despite `reg import` reporting success, and a
  UTF-8-with-BOM file is flatly rejected as "not a registry file." Both
  failure modes were hit and fixed while verifying this.)
- Dev-only fallback for testing deep links without packaging:
  `register-signflow-protocol.cmd` (manual, points at the dev jar).

### What the URL may and may never contain

`signflow://invitation/<token>` — the **token is the entire payload**.
Validated in `InvitationLinks.java` against
`[A-Za-z0-9_-]{32,}` before anything else touches it; anything that
doesn't match is treated as absent, not partially parsed. The token is an
opaque, single-use, server-issued, time-limited capability — it is never
a PDF, never a credential, never anything that grants access by itself.
The desktop app redeems it against the coordination server
(`GET /api/invitations/{token}`, server-side expiry/revocation/already-used
checks — see `InvitationService.java`); **the protocol link is never
trusted on its own**, exactly per the "never trust a request just because
it arrived through the registered scheme" principle. Malformed, expired,
revoked, and already-used tokens all render a friendly in-app or
server-rendered notice (`SignPageRenderer.notice(...)`), never a raw
exception.

### Already-running instance

Already implemented, not new: `SingleInstanceGuard.java` takes an OS file
lock as the mutex; a second launch that finds the lock held pings a
**loopback-only** (`127.0.0.1`) HTTP listener on a fixed local port
(61234), forwarding just the token as a query parameter, then exits. The
first instance raises its window and opens that invitation. If the first
instance doesn't answer within 4 seconds, the second launch offers to
force-close the stuck copy rather than silently doing nothing (the
original, worse failure mode this was built to replace).

**Verified directly, not just read**: launched a first instance, then
triggered `signflow://invitation/<token>` while it was running, and
confirmed in the log — `Activated by a second launch attempt (raised:
Sign in)` immediately followed by `Second launch forwarded an invitation
link` — that the handoff actually happens. (A genuinely separate second
machine was not used for this — see §6 for what that would still need to
confirm.)

**Threat model for that loopback listener**, made explicit since it's the
one piece of IPC in this design: it binds `127.0.0.1` only (no other
machine can ever reach it), accepts a single opaque token with no other
side effect than "open this one invitation in the UI," and — like the
protocol link itself — the token it forwards still goes through the same
server-side redemption and validation as any other launch path. There is
no broader command surface, no file-path or shell argument ever crosses
this listener, and it is not a general-purpose server: it exists only
because Windows doesn't deliver a second protocol launch to the first
process for you.

**This is not, and does not become, server mode.** Desktop mode's Spring
context is `WebApplicationType.NONE` always; the loopback listener is a
plain `com.sun.net.httpserver.HttpServer` instantiated directly inside the
desktop process for exactly this one purpose, not a side effect of
enabling the `server` profile. The two are architecturally unrelated.

## 3. PKCS#11 / USB token prerequisites

Not bundled, and cannot legally or safely be redistributed here — these
come from your token's vendor:

- The **PKCS#11 middleware/driver** for your specific smartcard or USB
  token (vendor-specific `.dll`), installed separately by the user/IT.
- Set `SIGNATURE_PKCS11_ENABLED=true` and `PKCS11_LIBRARY=<path to that
  .dll>` before launching a packaged build.

What **is** bundled: the JDK's own `SunPKCS11` provider (via the
`jdk.crypto.cryptoki` jlink module) and `jdk.crypto.ec` for EC
certificates — the generic PKCS#11 *wrapper*, not any vendor's specific
module.

**Missing/misconfigured prerequisites fail loudly, not silently** —
verified directly, repeatedly, this session:
`ProductionSafetyValidator` (`src/main/java/com/example/signer/config/ProductionSafetyValidator.java`)
refuses to start a packaged build and shows an explicit dialog
(`"No signing token is configured: set SIGNATURE_PKCS11_ENABLED=true and
PKCS11_LIBRARY..."`) rather than crashing or silently falling back to
anything. It additionally checks the configured library path actually
exists on disk before allowing startup past that point. The **mock
token is categorically unavailable to any packaged build** — blocked by
two independent checks (`-Dsignflow.packaged=true` and jpackage's own
`jpackage.app-version` system property), so there's no configuration
that accidentally ships a fake signing identity.

Private keys never leave the token; PINs are `char[]`, wiped immediately
after the token consumes them, and never logged (pre-existing design,
unchanged by this packaging work).

## 4. Code signing

**Not done here** — this project has no code-signing certificate, and
nothing in this repo claims one. Running the unsigned `.exe`/`.msi`
triggers Windows SmartScreen ("unknown publisher") and a one-time AV
behavioral-scan delay on first run (observed directly: ~40 seconds on
this machine, from real-time antivirus scanning a large unrecognized
binary — not a bug, not specific to this app).

To remove that friction for real distribution, you need a legitimate
code-signing certificate from a CA (e.g. DigiCert, Sectigo) or, for wider
reputation with Microsoft specifically, enrollment in the Microsoft Trusted
Signing program. Once you have one:

```powershell
# Using signtool (Windows SDK)
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 `
    /a "build\jpackage\SignFlow\SignFlow.exe"
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 `
    /a "build\dist\SignFlow-0.1.0.msi"
```

Sign both the raw `.exe` and the `.msi` — the MSI has its own separate
signature slot. Do this as a step after `package.sh`, before computing
`SHA256SUMS.txt` (the signature changes the file's bytes, so sign first,
checksum after).

## 5. Uninstall behavior

Per-user MSI uninstall (Windows Settings → Apps, or
`msiexec /x <ProductCode>`) removes only what the installer placed under
the user's own profile (`%LOCALAPPDATA%\SignFlow`) — it does not touch
`~/.signflow/` (mock-token keystore, dev-only anyway and absent from
packaged builds), user documents, or the `signflow://` registry key
left in place deliberately, matching Windows convention for protocol
handlers. Directly observed what happens if it's then clicked anyway: not
silent — Windows shows an explicit error ("Application not found", or
"Access is denied" when the registered path exists but the target file
doesn't) rather than failing quietly. That's an acceptable, visible
failure mode for a leftover key, not a bug; removing the key too is a
judgment call, not a requirement.

## 6. Verified vs. not verified

Actually run on this machine and confirmed by inspecting process state,
log files, or registry contents directly — not just "should work":

- ✅ Full test suite (`./mvnw test`) — 358/359 passing; the one failure
  (`WorkflowApiClientErrorTests`) predates this work and is an unrelated
  environment-dependent exception-message assertion, not a packaging
  regression.
- ✅ `package.sh` builds the zip, the per-user MSI, and now the
  checksum/manifest files, end to end.
- ✅ Per-user MSI: silent install (`msiexec /i ... /qn`) succeeds with
  exit code 0 from a **non-elevated** shell; Start Menu shortcut created;
  silent uninstall also succeeds non-elevated.
- ✅ `signflow://` self-registration fires on first launch of a packaged
  build and resolves to the correct installed path.
- ✅ Missing-PKCS#11-config path shows the intended dialog rather than
  crashing or hanging.
- ✅ Desktop mode confirmed `WebApplicationType.NONE` — no HTTP server
  starts merely from launching the desktop app.
- ✅ Already-running-instance forwarding — a second `signflow://` launch
  while the first was up correctly handed off the token (log-confirmed,
  §2).

Not verified here, and should not be assumed to work without separately
checking:

- ❌ Real PKCS#11 hardware (smartcard/USB token) — this machine has none;
  only the "library file is missing/misconfigured" path was exercised.
- ❌ Code signing — no certificate available in this environment.
- ❌ A genuinely separate second Windows machine for the
  already-running-instance / forwarded-invitation path — verified by
  launching a second process on the *same* machine, which exercises the
  same code path (the lock and the loopback listener don't distinguish
  "second process on this machine" from "second process on a different
  one"), but a true second-machine run was not performed.
- ❌ Upgrade-in-place (installing a newer MSI over an existing per-user
  install) — only clean install/uninstall were exercised.

## 7. Release checklist

1. `./mvnw verify` (or at minimum `./mvnw test`) — confirm the suite is
   green before packaging.
2. `./package.sh` — produces `build/dist/{zip,msi,SHA256SUMS.txt,
   release-manifest.json}`.
3. (Optional, requires your own certificate) sign the `.exe` inside the
   app-image and the `.msi`, then re-run step 2's checksum step manually
   or re-run `package.sh --skip-build` to regenerate `SHA256SUMS.txt`
   against the signed files.
4. Tag `vX.Y.Z` and push — `.github/workflows/release.yml` builds, tests,
   packages, and attaches all four files (zip, msi, checksums, manifest)
   to the GitHub Release automatically.
