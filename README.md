# SignFlow — PDF Digital Signature Desktop App

A local desktop application for signing PDF documents with cryptographic tokens (PKCS#11).
Built with Java 21, JavaFX, and Spring Boot, it supports sequential multi-signer workflows
(1, 2, or 3 signers) and chain-aware signature verification.

[![Build](https://github.com/CodeVoyager42-cyber/DesktopSign/actions/workflows/build.yml/badge.svg)](https://github.com/CodeVoyager42-cyber/DesktopSign/actions/workflows/build.yml)

## Features

- **Document-centric signing workspace** — page rendering, thumbnails, zoom, precise
  signature placement with drag-and-resize, and a live stamp preview
- **Sequential multi-signer workflow** — 1, 2, or 3 people sign one after another;
  each signature is an incremental PDF revision so previous signatures stay valid
- **Chain-aware verification** — later valid signatures counter-sign incremental
  updates instead of being rejected as tampering; every signature in the chain is
  checked and reported (signer, algorithm, timestamp, validity)
- **PKCS#11 hardware token support** — token detection, certificate selection,
  PIN authentication, and signing; private keys never leave the token
- **Mock token for development** — a software token with self-signed certificates
  so the full flow can be exercised without hardware
- **Streaming verification** — large PDFs are hashed in 64 KiB chunks without being
  fully loaded into memory, with a configurable size guard
- **Light/dark themes**, responsive layout, and an audit trail of workflow events

## Technology

| Component | Choice |
|---|---|
| Language / runtime | Java 21 |
| UI | JavaFX 21.0.7 (+ Ikonli icon font) |
| Application container | Spring Boot 3.5.3 (dependency injection only — no HTTP server) |
| PDF | Apache PDFBox 3.0.5 (incremental save, rendering, analysis) |
| Cryptography | BouncyCastle 1.80 (CMS/PKCS#7 signatures), SunPKCS11 provider |
| Build | Maven (wrapper included), GitHub Actions CI |

## Requirements

- **JDK 21+** (21 is the target; newer JDKs also work for local development)
- No separate Maven install needed — use the wrapper (`./mvnw` / `mvnw.cmd`)
- For real hardware signing: a PKCS#11 token/smartcard and its vendor middleware

## Download & Install

No JDK, Maven, or any other prerequisite is needed for the packaged build — it
carries its own Java runtime.

**Installable/portable builds** are produced by the release workflow:

1. Go to [Releases](https://github.com/CodeVoyager42-cyber/DesktopSign/releases)
2. Download the asset for the latest release:
   - `SignFlow-<version>-windows.zip` — portable: unzip anywhere and run
     `SignFlow.exe` (no installation at all)
   - `SignFlow-<version>.msi` — classic Windows installer with Start-menu
     shortcut and uninstaller (built when the CI runner has the WiX toolset)

To build the package yourself, from a machine with JDK 21+:

```bash
scripts/package.sh
```

Output lands in `build/dist/` (`build/jpackage/SignFlow/SignFlow.exe` is the
raw app-image). If the [WiX 3.x](https://wixtoolset.org/) toolset is
installed, an MSI installer is produced as well.

## Build & Run

```bash
# Run the desktop app (development mode)
./mvnw javafx:run

# Run the tests (90 tests)
./mvnw test

# Full build: compile, test, and produce the executable Spring Boot jar
./mvnw verify
```

The verify goal also produces a self-contained jar at
`target/pdf-signer-desktop-0.1.0-SNAPSHOT.jar`. To run it directly:

```bash
java --add-modules jdk.crypto.cryptoki \
     --add-exports jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -jar target/pdf-signer-desktop-0.1.0-SNAPSHOT.jar
```

(The `jdk.crypto.cryptoki` flags are required for raw PKCS#11 wrapper access.
Native access is granted to `ALL-UNNAMED` because in the packaged jar JavaFX
loads from the classpath rather than as a module — `--enable-native-access=javafx.graphics`
only applies to modular launches such as `./mvnw javafx:run`.)

## Quick Start (no hardware needed)

The app ships with a **mock token** so you can try the full workflow immediately:

1. Run `./mvnw javafx:run`
2. Log in with the development credentials (default `ISICOD` / `ISICOD` —
   see the configuration table below; change them for anything real)
3. Choose how many people will sign: **1 Person / 2 People / 3 People**
4. Open a PDF, place your signature, and confirm
5. When prompted for the token PIN, use the mock token's dev PIN: **`1234`**
6. Pick a signing certificate — the mock token offers:
   - `mock-signing-key` — RSA 2048 (`CN=Mock Signer, OU=SignFlow DEVELOPMENT MOCK TOKEN - NOT FOR PRODUCTION, …`)
   - `mock-ec-signing-key` — EC P-256 (`CN=Mock EC Signer, …`)

The mock token exists only in the **`dev` Spring profile**, which
`./mvnw javafx:run`, `run-app.cmd` and `run-server.cmd` activate. Its keys
are random and generated once per installation
(`~/.signflow/dev-mock-token.p12`, override with `SIGNER_MOCK_TOKEN_KEYSTORE`).
Without `dev` the application requires a real PKCS#11 token and refuses
to start otherwise; a production server rejects mock-token certificates.
See [docs/TRANSPORT-SECURITY.md](docs/TRANSPORT-SECURITY.md).

Each signer's turn repeats the same steps; after every signature the whole
signature chain is re-verified and shown per-signer (signer, algorithm,
timestamp, validity).

## Configuration

All settings are environment-overridable (they use Spring's `${ENV_VAR:default}`
syntax in `src/main/resources/application.yml`):

| Environment variable | Default | Description |
|---|---|---|
| `SIGNATURE_LOGIN_USERNAME` | `ISICOD` | Application start-up gate username. **Development default — change it for any real deployment.** |
| `SIGNATURE_LOGIN_PASSWORD` | `ISICOD` | Application start-up gate password. **Development default — change it for any real deployment.** |
| `SIGNATURE_PKCS11_ENABLED` | `false` | When `true`, use a real PKCS#11 device (token/smartcard/SoftHSM2) instead of the mock token. |
| `PKCS11_LIBRARY` | *(empty)* | Absolute path to the PKCS#11 module for the target platform (`.dll` on Windows, `.so` on Linux, `.dylib` on macOS). Required when `SIGNATURE_PKCS11_ENABLED=true`. |
| `PKCS11_SLOT_INDEX` | `0` | Index into the PKCS#11 slot list to select which token slot to use. |
| `SIGNATURE_VERIFICATION_MAX_FILE_BYTES` | `536870912` (512 MiB) | Maximum PDF size the verifier will process; larger files are refused with a friendly error. |
| `SIGNER_LOG_FILE` | `%TEMP%/signer-app.log` | Log file location (`${java.io.tmpdir}/signer-app.log`). |

Example — running against a real token with custom credentials:

```bash
SIGNATURE_LOGIN_USERNAME=alice \
SIGNATURE_LOGIN_PASSWORD='correct-horse' \
SIGNATURE_PKCS11_ENABLED=true \
PKCS11_LIBRARY='C:\Program Files\Vendor\pkcs11.dll' \
./mvnw javafx:run
```

### Server secrets (SMTP etc.)

`run-server.cmd` loads secrets such as the SMTP settings from a file kept
**outside the project folder**:

1. `%SIGNFLOW_SECRETS_FILE%`, when that variable is set;
2. otherwise `%USERPROFILE%\signflow-secrets\mail-secrets.env`.

Lines are `KEY=VALUE` or `export KEY=VALUE`, values may be quoted, and `#`
lines are comments. See [.env.example](.env.example) for the available keys.
A `mail-secrets.env` left in the project folder is **not** loaded — the
launcher prints a warning asking you to move it. Never commit secrets:
`.gitignore` excludes `mail-secrets.env` and every `*.env` except
`.env.example`.

## Project Structure

```
.
├── src/main/java/com/example/signer/
│   ├── ui/                 JavaFX desktop app
│   │   ├── (root)          App shell: SignerFxApplication, AppNavigator, single-instance guard
│   │   ├── dialog/         Login, home screen, invitation and inviter dialogs
│   │   ├── workspace/      Local signing (1–3 signers on this PC, continue a workflow PDF)
│   │   ├── remote/         Server-backed screens (tasks, remote signing journey, wizard)
│   │   ├── kit/            Reusable controls
│   │   └── support/        Background threads, error messages, helpers
│   ├── client/             HTTP client for the SignFlow server + remote signing service
│   ├── server/             Coordination server (Spring Boot, profile "server")
│   │   ├── api/            REST controllers and API contracts
│   │   ├── auth/           JWT, refresh tokens, guest sessions, accounts
│   │   ├── invites/        Invitations, emails, public sign page
│   │   ├── workflow/       Workflow aggregate, state machine, service, stores
│   │   └── db/             JPA entities, repositories, PostgreSQL store
│   ├── workflow/           Portable workflow manifest, reconciliation, local engine
│   ├── signing/            PDF signing (incremental revisions, CMS, stamp rendering)
│   ├── verification/       Chain-aware PDF signature verification
│   ├── pdf/                Document analysis, metadata, rendering
│   ├── token/              Token abstraction + state machine
│   │   ├── mock/           Development token
│   │   └── pkcs11/         Real SunPKCS11-backed token
│   ├── config/             Typed configuration properties
│   └── exception/          Error codes and the service exception
├── src/main/resources/     application.yml, Flyway migrations (db/migration), UI styles
├── src/test/java/          Tests, in the same packages as the code they test
├── docs/                   Design notes; docs/architecture/ has the sequence diagrams
├── scripts/                package.sh, db-console.cmd, (un)register-signflow-protocol.cmd
├── tools/                  Build helpers (icon generation)
├── run-app.cmd             Start the desktop app
└── run-server.cmd          Start the coordination server
```

The desktop app and the server are built from the same jar; the Spring
profile decides which one starts. The desktop context (`SignerApplication`)
runs with `web-application-type: none`; only the server profile starts a web
server.

## How Signing Works

```
UI (JavaFX workspace)
  → WorkflowSigningEngine (signer state machine, one signer at a time)
    → TokenService (detect → select certificate → PIN login)
    → DefaultPdfSigningService (incremental PDF revision + BouncyCastle CMS)
    → DefaultPdfVerificationService (re-verifies the whole signature chain)
```

- Signing is **always sequential**; each signature is an incremental update, so
  the previous revision's bytes are never touched and earlier signatures remain
  cryptographically valid
- Existing signatures in an opened PDF are treated as read-only
- The private key stays on the token; only a digest is signed
- PINs are handled as `char[]` and never logged or persisted
- On a wrong PIN the signer can retry; token removal and other failures map to
  user-safe messages, not raw PKCS#11 errors

## Development

- **CI:** GitHub Actions builds and tests on JDK 21 (Temurin) for every push and
  pull request — see `.github/workflows/build.yml`
- **Compiler note:** the build intentionally uses `-source/-target 21` rather
  than `--release 21`; the `--release` flag is incompatible with the
  `jdk.crypto.cryptoki` add-exports the raw PKCS#11 wrapper requires (verified
  empirically — see the comment in `pom.xml`)
- **App icon:** `docs/icon-source.png` is the master brand image (any square
  picture). `scripts/package.sh` regenerates from it the JavaFX window icon
  (`src/main/resources/ui/app-icon.png`) and the Windows icon
  (`build/SignFlow.ico`, used by the exe/installer) via
  `tools/BuildIcon.java`. To rebrand, replace the source picture and re-run
  `scripts/package.sh` — or `java tools/BuildIcon.java` for the icons alone
- Logs go to `%TEMP%/signer-app.log` by default; `com.example.signer` is at
  DEBUG level for tracing token and workflow behavior

## Security Notes

- The default login credentials (`ISICOD`/`ISICOD`) and mock PIN (`1234`) are
  **development conveniences only** — the mock token exists only in the `dev`
  profile, its keys are random per installation and are not hardware-backed;
  production servers reject its certificates
- Configure real credentials and a real PKCS#11 library via the environment
  variables above before any production use
- The desktop app talks to the SignFlow coordination server over HTTPS (plain
  HTTP only to this machine's loopback address, in the `dev` profile) and opens
  **no listening network port** of its own; the single-instance hand-off between
  two launches uses a private local socket in a per-user directory, not TCP

## License

Released under the [MIT License](LICENSE).
