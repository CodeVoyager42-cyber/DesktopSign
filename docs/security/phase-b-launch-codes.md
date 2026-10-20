# Phase B — secure desktop hand-off and launch codes

Status: **implemented, then remediated after the Phase B security review**
(2026-10-19). Tested on Linux only. **Nothing here has been run on Windows
yet** (§8). Follows `docs/architecture/website-launch-code-plan.md` and
decisions D1–D5.

Scope: the desktop and server halves of "sign from the website". There are
no website pages yet (Phase C onward). Invitations, guest signing, desktop
username/password login and local PKCS#11 signing are unchanged. Private
keys and PINs never leave the PC.

## 1. What a launch code is — and is not

A launch code lets a desktop open **one signing task** for **one signed-in
website account**, for a short time. It is a *convenience credential* that
carries the website session to the desktop app. It is **not** proof of who
is at the keyboard, and it is **not** proof of who holds a smart card.

What a signature actually proves is decided by the certificate:

- The server accepts a signed revision only if its newest signature
  verifies cryptographically and was made with **the certificate already
  registered for that signer slot**
  (`RemoteWorkflowService.validateSubmittedPdf`).
- A certificate fingerprint identifies a **certificate**, not a person. It
  shows that whoever signed controlled that certificate's private key, which
  normally means holding the card and knowing its PIN. It does **not** show
  that this is the person the account belongs to.
- Binding a certificate to a signer account therefore needs an appropriate
  **identity-verification or enrolment process**, outside what SignFlow
  checks today. Today a slot gets its certificate either when the workflow
  is created (the sender enters the fingerprint) or when the signer
  registers it from the signed-in desktop or the e-mailed guest flow. In
  every case, "first card wins" (open gap G2, Phase 3).

## 2. Review finding H2 (fixed): a stolen code could attach the thief's card

**Was:** a task session reused the guest allow-list, which includes
`POST /api/workflows/{id}/signers/{self}/certificate`, and codes were issued
for slots that had no certificate yet. Anyone who obtained a launch code
could open the session, register **their own** card for the victim's slot,
and then sign as the victim. The earlier version of this document wrongly
said a stolen code was useless "without the card".

**Now:**

| Change | Where |
|---|---|
| Task sessions have **their own allow-list**: only `GET /task`, `GET /document`, `POST /claim`, `POST /signatures`, and only for the session's workflow. Certificate binding, workflow and invitation management, audit, revisions, lists, issuing codes and account endpoints are all refused (403). | `auth/TaskAccessPolicy`, `security/AuthenticatedRequestFilter` |
| **No code for a slot without a registered certificate**: 409 `CERTIFICATE_NOT_BOUND` ("connect your card in SignFlow first"). A launch can never register a certificate. | `RemoteWorkflowService.requireLaunchableSlot` |
| On **every request** the session is re-evaluated: the code is consumed and not revoked; the account is active and its e-mail verified; the slot still belongs to the account, is bound and unsigned, and the workflow is active. Once the slot is signed or the workflow is cancelled, completed or failed, only `GET /task` (the result) still answers; everything else is 403 `TASK_SESSION_FINISHED`. | `LaunchCodeService.taskSessionState`, `RemoteWorkflowService.taskSlotState` |

**What a stolen launch code still gives**, at most, until it expires (2
minutes unused) or the session ends (30 minutes, or earlier when the task
ends or is revoked):

- the status of that one task, and the document revision waiting for the
  victim's signature (a confidentiality loss for that document);
- the ability to take the turn in a flexible-order workflow (`/claim`);
- **no** way to sign as the victim *unless the thief also controls the
  certificate already registered for the slot*, i.e. the victim's card
  and PIN, or a card wrongly registered earlier (see G2 above).

Normal certificate registration by the signed-in signer and by invited
guests is unchanged (tests in §7).

## 3. Review finding H1 (fixed): the Windows hand-off must fail closed

A second SignFlow started by a link hands the link to the running one over
a local socket in a per-user run directory, with mutual HMAC proof of a
per-run key. The review found the Windows permission step only *tried* to
tighten access, swallowed failures, and found the user by name.
`SingleInstanceGuard` now does the following:

| Measure | Detail |
|---|---|
| **Identity without name lookup** | The current account is the owner of a file the process has just created (CREATE_NEW, random name, per-user temp directory): on Windows the token's owner SID, elsewhere its uid. |
| **Directory created private** | Windows: an explicit ACL (this account and `NT AUTHORITY\SYSTEM`, inheritable to new files). POSIX: mode 700. |
| **Verified, not assumed** | It must be a real directory: not a link and not a junction or other reparse point (`isOther`). It must be owned by this account. Its permissions are re-applied, then **read back**. Windows: every ALLOW entry, inherited ones included, must name this account or SYSTEM, and this account must be able to read and write. POSIX: exactly 700. |
| **Key created private from the outset** | It is created under a temporary name with owner-only permissions (ACL attribute, or 600, set at creation), written, verified, then moved into place atomically. |
| **Second launch checks before reading the key** | It checks the directory again, then the key file: a plain 32-byte file (no link), owned by this account, owner-only. It reads exactly 32 bytes without following links. |
| **Fail closed** | If the directory cannot be verified, the app runs on its own (`STANDALONE`) and starts no listener. If the key or socket cannot be verified, nothing is forwarded (`ALREADY_RUNNING_UNVERIFIED`). If the listener cannot be set up, no listener runs and the key is removed. |
| **Unchanged** | No network port. The running copy proves itself before anything is sent. The message HMAC is bound to fresh nonces (no forgery, no replay). Values are validated strictly on receipt and never logged. |

**Not protected against:** malware running as the *same* Windows account (it
can read the key, the app's memory, or the screen), and administrators.
Replacing a key file between the check and the read requires write access
to the verified directory, so only this account or SYSTEM can do it.
**The Windows ACL calls themselves have not been executed on Windows** (§8).
On Linux only the decision rule is tested, against synthetic principals.

## 4. Review finding M1 (fixed): start-up and recovery

| Was | Now |
|---|---|
| The listener started only after Spring and the window, several seconds after the lock. A second click meanwhile looked "stuck" and was offered **"Restart SignFlow" → force-kill** of the starting copy. | The listener starts **right after the lock** (`init()`), before Spring. Links arriving before the window exists are **queued** (at most 8) and opened once it is ready (`ActivationQueue`). |
| "Lock held, no socket" counted as dead. | It means *starting, or channel unavailable*. The second launch retries for up to 8 seconds, then says SignFlow is already open and that the link was not passed on. |
| A copy whose listener failed could be force-killed on the next click, even mid-signing. | **There is no kill path any more**, not even with confirmation. A copy that is busy, stuck or channel-less is reported, never terminated. |
| A browser firing the link twice opened it twice. | The same link within 2 minutes opens once (only a hash is kept). |
| A closing copy refused links. | It answers "closing"; the second launch waits for the lock and starts itself. |
| The message "open the link again from there" had no way to do so. | The message now points to the website (launch link) or the e-mail (invitation). |

## 5. Review finding M2 (fixed): the desktop's version

- The version comes from `signflow-build.properties`, which Maven fills in
  (`@project.version@`, the starter parent's `@` delimiters; the `pom.xml`
  `<resources>` block repeats the parent's configuration and adds this one
  file). The manifest-based lookup was removed.
- If the file is missing, unfiltered or malformed, the app sends
  **`unknown`**, never a release-looking guess.
- The server refuses `unknown` with 426 unless
  `SIGNER_DESKTOP_ACCEPT_UNKNOWN_VERSION=true`. The `dev` profile sets it,
  and production refuses to start with it (`ProductionSafetyValidator`). The
  launch is then recorded as "unknown".
- Pre-release order now follows Semantic Versioning 2.0.0 §11
  (`rc.2 < rc.10`; numeric identifiers sort below alphanumeric ones), and
  empty identifiers are refused. As before, `1.2.0-beta < 1.2.0` and the
  current `0.1.0-SNAPSHOT` meets the default minimum `0.1.0-SNAPSHOT`.
- **This is compatibility protection, not authentication**: the client
  states its version and the server cannot verify it.

## 6. Launch codes (unchanged parts) and other remediations

| Property | Implementation |
|---|---|
| Random, single use, 2 minutes, hash only | 32 bytes from `SecureRandom` (43 characters, base64url); only the SHA-256 is stored (`CHAR(64)`). |
| Atomic consumption | One conditional `UPDATE … consumed_at IS NULL AND revoked_at IS NULL AND expires_at > now`, committed on its own. |
| Bound to user, task, purpose and version | Re-checked at exchange; a newer code for the same slot revokes the older unused one. |
| Indistinguishable refusals | One 400 `LAUNCH_CODE_INVALID` body for unknown, malformed, used, expired, superseded and revoked codes, or a changed task. |
| Version gate first | 426 before the code is looked at, so the code is not spent. |
| Issue limit (**race fixed**) | 5 per user per minute, counted under a row lock on the user (`UserRepository.lockByUsername`). Before: 14 racing requests issued 10 codes; now exactly 5. |
| Exchange limit (**own bucket**) | Per client address, now separate from the sign-in/registration bucket, so flooding one no longer locks the same address out of the other. |
| Safe logging | Logs name the workflow and slot only, never the code, its hash or the token. The code travels only in a POST body. **Launch links are no longer passed to Spring Boot** (only `--option` arguments are). |
| Website notice (D3) | `GET /api/desktop/launches` and `POST …/{id}/revoke`; audit `DESKTOP_SESSION_OPENED` / `_REVOKED`. No website shows them yet. |

## 7. Residual risks

| Risk | Severity | Note |
|---|---|---|
| Certificate registration ("first card wins", G2) | High (open) | A launch can no longer register a certificate, but the signed-in desktop and guest flows still can, without matching the certificate to the person (§1). Needs an enrolment / identity-verification decision. |
| Rogue `signflow://` handler (F3) | Medium | Any program the user installs can claim the scheme and receive codes. Since H2: a thief can read that task's document and take a flexible turn, but cannot register a card or sign without the slot's registered certificate. |
| Code on the process command line | Low | Windows passes the link to `java.exe`. Process-creation auditing and EDR tools may record it. Single use, 2 minutes. |
| Shared-address rate limits | Medium (availability) | Limits are per client address, in memory, per server node. Behind NAT, a proxy, or a missing forwarded-address configuration (Phase 2.1 requires one when TLS is offloaded), many users share one bucket and one flood blocks them all. Guessing a 256-bit code is infeasible anyway; this is a denial-of-service risk, not a confidentiality one. |
| Same-account malware, administrators | — | Out of scope for any local IPC (§3). |
| Installed builds don't register `signflow://` | — (functional) | `scripts/package.sh` (jpackage) registers nothing; only `scripts/register-signflow-protocol.cmd` (per-user, development jar) does. Not claimed working. |
| Website security (sessions, CSRF, sign-up, verification) | — | Phases C–F. Only the verified-e-mail flag is enforced now. |

## 8. Tests and what still needs Windows

Linux results (JDK 21, PostgreSQL 16, explicitly configured): see the
remediation report. New since the review:
- `TaskSessionScopeHttpTests` (7; 6 of them fail on unfixed Phase B)
- `SingleInstanceGuardTests` (17, including 3 with a real second JVM)
- `WindowsAclPolicyTests` (4, pure logic)
- `ActivationQueueTests` (5)
- `SecondLaunchMessageTests` (3)
- `AppVersionTests` (3)
- `LaunchUnknownVersionDevTests` (1)
- extra cases in `DesktopVersionTests`, `LaunchCodeHttpTests`, `LaunchExchangeRateLimitTests` and `ProductionSafetyValidatorTests`

**Must be run on Windows before claiming Windows behaviour:**
1. `mvnw test` with PostgreSQL configured. On Windows the POSIX-only
   permission tests report as *skipped*, and the owner-change tests need root,
   so they skip too.
2. `icacls "%LOCALAPPDATA%\SignFlow\run"`, `…\ipc.key` and `…\ipc.sock` while
   SignFlow runs: only the user and SYSTEM, no other entries.
3. As a second standard account: reading `ipc.key`, connecting to `ipc.sock`
   and creating files in the folder must all fail.
4. With an extra ACL entry added to the folder (`icacls … /grant Users:R`),
   the next start must reset and verify it, or refuse.
5. A junction in place of the run folder must give "running on its own".
6. Domain / Azure AD account and a non-ASCII profile path: hand-off works.
7. Two real processes: invitation and launch links forwarded; double click
   during start-up opens once; `netstat -ano | findstr 61234` empty.
8. End SignFlow in Task Manager, start again: stale files replaced.
9. Edge, Chrome and Firefox opening `signflow://launch?…`: record only the
   shape and length of the argument the app receives.
10. `mvnw package`, then the packaged jar and jpackage build: the server log
    shows `app 0.1.0-SNAPSHOT`, not `unknown`.
11. Full signing through a task session with the real card, only with the
    card holder's agreement.
12. A **pre-existing `instance.lock` with an extra ACL entry**: while SignFlow
    is stopped, `icacls "%LOCALAPPDATA%\SignFlow\run\instance.lock" /grant
    *S-1-5-32-545:R` (the SID, because the `Users` name is localized). The next
    start must re-apply and verify the file's permissions — `icacls` on it then
    shows only the user and SYSTEM — or refuse the guard and run on its own.
    Unlike `ipc.key` and `ipc.sock`, the lock file survives a run and is never
    rewritten, so a widened ACL would otherwise persist.
