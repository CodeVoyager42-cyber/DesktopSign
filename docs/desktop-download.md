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
the "To sign" tab when something is waiting, the hand-off screen's fallback,
and the sign-up confirmation. It shows the version and size, the three
install steps, what the machine needs, the SmartScreen warning in plain
words, the checksum with a copy button and the PowerShell command to compare
it, and a troubleshooting section. When nothing is published it says so and
points at the administrator, which is the truth rather than a broken button.
