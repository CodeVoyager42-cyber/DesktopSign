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
   - `mock-signing-key` — RSA 2048 (`CN=Mock Signer, O=Signer Dev, C=FR`)
   - `mock-ec-signing-key` — EC P-256 (`CN=Mock EC Signer, O=Signer Dev, C=FR`)

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

## Project Structure

```
src/main/java/com/example/signer/
├── config/        Typed configuration properties (login, PKCS#11)
├── exception/     Error codes and the service exception hierarchy
├── pdf/           Document analysis, metadata, rendering
├── signing/       PDF signing (incremental revisions, CMS, stamp rendering)
├── token/         Token abstraction + state machine
│   ├── mock/      In-memory development token
│   └── pkcs11/    Real SunPKCS11-backed token
├── ui/            JavaFX application, dialogs, theming
│   ├── support/   Shared UI helpers
│   └── workspace/ Signing workspaces (single + multi-signer), coordinate mapping
├── verification/  Chain-aware PDF signature verification
└── workflow/      Signer state machine, workflow orchestration, audit trail
```

Spring Boot is used purely as the application context / dependency-injection
container (`web-application-type: none`); nothing listens on a network port.

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
- Logs go to `%TEMP%/signer-app.log` by default; `com.example.signer` is at
  DEBUG level for tracing token and workflow behavior

## Security Notes

- The default login credentials (`ISICOD`/`ISICOD`) and mock PIN (`1234`) are
  **development conveniences only** — the mock token's keys are generated in
  memory at runtime and are not hardware-backed
- Configure real credentials and a real PKCS#11 library via the environment
  variables above before any production use
- The application makes no network connections; there is no HTTP surface
