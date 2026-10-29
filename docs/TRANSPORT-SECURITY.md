# Transport security and development vs. production

SignFlow has two runtime postures, selected by the Spring profile `dev`.

| | Development (`dev` profile) | Production (no `dev` profile) |
|---|---|---|
| Signing token | Mock token, random keys generated per installation (`~/.signflow/dev-mock-token.p12`, PIN `1234`) | Real PKCS#11 token required (`SIGNATURE_PKCS11_ENABLED=true`, `PKCS11_LIBRARY`); the app refuses to start without one |
| Server accounts | alice / bob / charlie seeded, plaintext passwords hashed at startup | No built-in accounts; plaintext passwords refused |
| JWT secret | Random per process when `SIGNER_SERVER_JWT_SECRET` is empty | `SIGNER_SERVER_JWT_SECRET` (≥ 32 bytes) required; startup fails without it |
| Mock-token certificates | Accepted by the server | Rejected (`DEVELOPMENT_IDENTITY_REJECTED`) |
| Transport | Plain HTTP allowed | HTTPS required (plain HTTP only to `localhost`/`127.0.0.1`) |
| Email | Console mailbox allowed | SMTP required; invitation links must be `https://` |

`run-app.cmd`, `run-server.cmd` and `mvnw javafx:run` are **development
launchers** and activate `dev`. Installed builds (`scripts/package.sh`) carry
`-Dsignflow.packaged=true` and can never run `dev`.

Every production rule is checked at startup by `ProductionSafetyValidator`;
a violation stops the process with one message listing all problems
(the desktop shows it in a dialog).

## Coordination server: enabling TLS

Pick one:

1. **Spring Boot TLS** — give the server a certificate issued by a CA the
   clients trust:

   ```
   SERVER_SSL_ENABLED=true
   SERVER_SSL_KEY_STORE=C:\signflow\server.p12
   SERVER_SSL_KEY_STORE_PASSWORD=...
   SERVER_SSL_KEY_STORE_TYPE=PKCS12
   SERVER_PORT=8443
   ```

2. **TLS-terminating reverse proxy** (IIS/ARR, nginx, Caddy, a cloud load
   balancer) in front of a server bound to `127.0.0.1` only, then set
   `SIGNER_TLS_OFFLOADED=true`. Do not expose the plain-HTTP port to the
   network in this mode.

Also set `SIGNER_PUBLIC_BASE_URL=https://<host>` so emailed invitation links
are HTTPS, and `SIGNER_SERVER_URL=https://<host>` on every desktop.

## How the desktop trusts the server certificate

The desktop uses the JDK `HttpClient` with **full certificate and host-name
validation; it is never disabled**.

- Installed builds start with `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`,
  so the server certificate is trusted exactly like in a browser on the same
  PC: a certificate from a public CA (e.g. Let's Encrypt) works out of the box;
  an internal company CA works once IT deploys it to the Windows "Trusted Root
  Certification Authorities" store (Group Policy / Intune).
- Self-signed server certificates are **not** supported as a shortcut. If you
  must use one, import it into the Windows root store (or a dedicated
  truststore passed with `-Djavax.net.ssl.trustStore=`), never switch off
  validation.
- Running from source (`java -jar`, `mvnw`) uses the JDK `cacerts` truststore
  unless the same `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` option is
  given.

Plain `http://` is refused by the client for any host other than this
machine's loopback address unless the `dev` profile is active.

## What travels over the connection

Passwords (login/registration), access JWTs, refresh tokens, invitation
tokens and PDF revisions. All of them therefore require HTTPS in production.
The server never logs raw invitation tokens, passwords, JWTs or refresh
tokens; in console (development) mail mode the full email is printed to
standard output only and the log contains a redacted link.

The browser invitation page (`/invitations/{token}`) no longer offers a PDF
download: the document is reviewed in the desktop app after sign-in, through
the authenticated workflow API.
