# Windows packaging — pinned prerequisites and how to run it

How the Windows release is built on a developer machine, and what each
prerequisite is doing. The build process itself (`scripts/package.sh`) and
what it deliberately does not do are covered in
[desktop-download.md](desktop-download.md) §1; this file covers only the
pinned, verified front door: **`scripts/package-windows.sh`**.

```bash
# A release for a real server (the WHOLE CI contract, see §Why each pin):
scripts/package-windows.sh --server-url https://signflow.example.com

# For local testing only (marks the app, allows http:// + loopback):
scripts/package-windows.sh --local --server-url http://localhost:8080
```

Arguments are exactly `scripts/package.sh`'s — pass through the same ones.
Outputs are unchanged and land in `build/dist/`:

| File | What it is |
|---|---|
| `SignFlow-<version>.msi` | **the deliverable** — per-user installer, registers `signflow://`, clean-uninstallable |
| `SignFlow-<version>.msi.sha256` | its checksum (format of `sha256sum`) |
| `SignFlow-<version>-windows.zip` | portable build; registers nothing |
| `SignFlow-<version>-windows.zip.sha256` | its checksum |
| `release-manifest.json` | build provenance: commit, time, per-artefact checksum |

The script verifies what it produces before reporting success: both
artefacts must exist **and** their `.sha256` files must match them,
or the script exits non-zero.

---

## 1. Why each pin exists

### Packaging JDK = OpenJDK 26.0.2 — the template pairing, not a whim

`packaging/windows/main.wxs` **replaces** the `main.wxs` inside the JDK's
jpackage (the `overrides.wxi` hook can only set variables, not add the
`signflow://` registry component — see the file's own header). A replacement
means it must track the JDK generation that compiles it:

| Packaging JDK | Symptom |
|---|---|
| **21.x** | `light.exe` exits **94**: `error LGHT0094 : Unresolved reference to symbol 'WixComponentGroup:FragmentOsCondition'` — the JDK 21 template does not define that group, nor ship `msica.dll` (21 uses `wixhelper.dll`) |
| 26.0.x ≠ 26.0.2 | the template-drift check screams loudly (and smaller CA-name differences surface); build continues but is not the tested pairing |

Hence the **pin: OpenJDK 26.0.2.** `scripts/package.sh` alone prefers "the
newest jpackage it can find" — exactly how a developer's JDK 21 eclipsed
their JDK 26 and produced the LGHT0094 above. `package-windows.sh` closes
that hole: it discovers only a 26.0.2 (or takes `$PACKAGING_JAVA_HOME`),
verifies `java -version` says `26.0.2`, confirms `jpackage` is present, and
refuses anything else — without buying a second JDK for the jar itself: the
Maven build that produces `target/pdf-signer-desktop-*.jar` (CI: Java 21)
is a **separate role** from the jpackage/jlink stage.

### WiX Toolset 3.14.1 — the last v3 line

`candle.exe` and `light.exe` are the compiler and linker jpackage's
MSI stage drives. Both must be on PATH (or discovered) and both must report
`3.14.1` (the check parses `candle.exe -?`).

Two install shapes serve this:

1. **Installer** — `wix314.exe`, simplest for a dev machine; by default it
   installs to `C:\Program Files (x86)\WiX Toolset v3.14\bin` (note:
   32-bit `Program Files (x86)`, which is what the script's discovery order
   scans first).
2. **Portable** — `wix314-binaries.zip`, unpack into `$HOME/tools/wix314`
   (candle.exe at the top). No admin needed, nothing installed machine-wide,
   match the layout `scripts/package.sh` finds by name.

Both zips come from [wixtoolset/wix3 releases](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm).
WiX v4 — a different toolchain and hook model — is **not** accepted: it is
incompatible with jpackage's template expectations, so the check refuses it
loudly rather than failing obscurely at link time.

### The template drift check (informational, loud)

`scripts/package.sh` extracts the JDK-in-use's own `main.wxs` from
`jdk.jpackage.jmod` and compares it against
`packaging/windows/main.wxs.jdk-base` — the pristine 26.0.2 copy the
template is derived from. Silence means the pairing is clean; a warning
means a new jpackage generation changed the template and the project copy
must be re-diffed and re-ported before trusting a built MSI with it.

---

## 2. One-time setup

Nothing here needs administrator rights except the installer flavour of
WiX (and that has the portable alternative).

1. **OpenJDK 26.0.2** with jpackage — e.g. from
   [jdk.java.net/26/](https://jdk.java.net/26/) or an IDE's JDK manager
   (writes to `$HOME/.jdks`, which the script's first discovery root reads).
2. **WiX 3.14.1** — installer or portable, either of §1's two shapes.
3. **Git Bash + Maven prerequisites already in the repo** —
   `scripts/package.sh` handles Maven via `./mvnw` itself; the jar can also
   be built separately (this script's own `--skip-build` path reuses
   `target/pdf-signer-desktop-*.jar`).

---

## 3. Verifying the resulting installer (`signflow://`)

The MSI is a per-user install (no UAC) whose registry write lives under
HKCU, as a component of the product — installed with the files' transaction,
removed with the uninstaller. To verify on a test machine, after running
the MSI:

```bat
reg query HKCU\Software\Classes\signflow /ve
reg query HKCU\Software\Classes\signflow\shell\open\command
```

Expect `URL:SignFlow Protocol`, an empty `URL Protocol` value, and a
`"[INSTALLDIR]SignFlow.exe" "%1"` command pointing at the actual install
directory (`HKMU` resolves to `HKCU` for this per-user install). Then click a
`signflow://invitation/<token>` link from the invitation page — it should
open the installed app at that specific task.

Uninstall from *Settings → Apps* (per-user entries appear for the user,
not machine-wide) and re-run the same `reg query` — every `signflow` key
should be gone: the three registry keys carry
`ForceDeleteOnUninstall="yes"` and are registered as a component of the
product, so the uninstall transaction removes them. Leaving a scheme
handler pointing at a deleted executable would be precisely the failure
this installer-is-the-owner rule exists to prevent.

> **SmartScreen:** the MSI is not code-signed. Windows shows the
> "unknown publisher" warning on first run — that is documented behavior
> and is NOT a packaging bug. See docs/desktop-download.md on what to do
> about it (buy and wire a code-signing certificate; do not work around
> the warning).

---

## 4. Troubleshooting

| Message | Meaning, fix |
|---|---|
| `OpenJDK 26.0.2 was not found` | No 26.0.2 discovered — install it (§1) or set `PACKAGING_JAVA_HOME`. **Never** downgrade the script to accept your 21 for convenience: see the LGHT0094 row below |
| `jpackage is missing` | A JRE, not a full JDK. The dev JDK must include jpackage |
| `WiX Toolset 3.14.1 … not found` | Neither Program Files root nor `$HOME/tools/wix314` had 3.14.1 candle+light. Install per §1, or set `WIX_BIN` |
| `… did not look like the JDK 26.0.2 template` | The template was edited or reverted to a JDK 21 layout. Restore from git and re-apply team-approved edits |
| `error LGHT0094 : Unresolved reference to symbol '…FragmentOsCondition'…` | **You bypassed this script and ran `scripts/package.sh` with a JDK 21 `JAVA_HOME`.** Template/JDK generation mismatch. Re-run through `package-windows.sh` |
| Template drift `!! WARNING` at build | A 26.0.x much newer than 26.0.2 — re-diff `build/jdk-wxs/…/main.wxs` against `main.wxs.jdk-base` and port accordingly (see §1's drift rule) |
| `checksum mismatch for …` | The artefact and its `.sha256` disagree — the artifact is corrupt/incomplete. Rebuild; never reconstruct the `.sha256` by hand to force it to pass |
