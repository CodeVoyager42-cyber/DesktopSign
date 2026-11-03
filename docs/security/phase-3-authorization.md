# Security Phase 3 — authorization

Status: **implemented** (2026-10-19). Planned after the Phase 2 review; the
plan's decisions were taken with the recommended defaults.

Rule: **the server decides, per request, from server-owned facts** (who
created the workflow, which slot belongs to which account, whose turn it is,
the workflow state). A workflow id, an invitation id, an invitation link or a
JavaFX button never grants anything on its own. PKCS#11 signing is unchanged:
it still happens only on the signer's PC, with their card and PIN.

## 1. Who is who

| Actor | How the server knows it |
|---|---|
| Owner | `workflows.created_by` = authenticated user |
| Signer | a `workflow_signers` slot whose `username` = authenticated user |
| Current signer | the signer whose turn it is (`currentSigner()`; FLEXIBLE: the claim holder) |
| Guest | guest JWT for exactly one workflow + slot + invitation, issued only after the e-mailed code (`GuestAccessPolicy`) |
| Other user | authenticated, none of the above |
| Anonymous | no valid token: 401 (Phase 2) |

## 2. Authorization matrix

✅ allowed · ⛔ refused (status) · — not applicable. "404 = missing" means
the answer is byte-for-byte the one for a workflow or invitation that does
not exist.

| Endpoint | Owner | Signer (turn) | Signer (not turn) | Other user | Guest (own slot) | Guest (other wf) |
|---|---|---|---|---|---|---|
| `POST /api/workflows` | ✅ | ✅ | ✅ | ✅ | ⛔ 403 | ⛔ 403 |
| `GET /api/workflows/{id}` | ✅ | ✅ | ✅ | ⛔ 404 = missing | ⛔ 403 | ⛔ 403 |
| `GET /my-tasks`, `/owned`, `/signing-tasks` | own only | own only | own only | own only | ⛔ 403 | ⛔ 403 |
| `GET /{id}/task` | ✅ | ✅ | ✅ | ⛔ 404 = missing | ✅ | ⛔ 403 |
| `POST /{id}/claim` | ⛔ 403 (not a signer) | ✅ | ✅ FLEXIBLE / ⛔ 409 SEQUENTIAL | ⛔ 404 = missing | ✅ | ⛔ 403 |
| `GET /{id}/eligible-signers` | ✅ | ✅ | ✅ | ⛔ 404 = missing | ⛔ 403 | ⛔ 403 |
| `POST /{id}/signers/{self}/certificate` | ⛔ 403 (not self) | ✅ once, while DRAFT | ✅ once, while DRAFT | ⛔ 404 = missing | ✅ once | ⛔ 403 |
| `GET /{id}/document` | ⛔ 409 not your turn | ✅ | ⛔ 409 | ⛔ 404 = missing | ✅ on turn | ⛔ 403 |
| `GET /{id}/revisions`, `/audit` | ✅ | ✅ | ✅ | ⛔ 404 = missing | ⛔ 403 | ⛔ 403 |
| `POST /{id}/signatures` | ⛔ 409 | ✅ (certificate must match the slot) | ⛔ 409 | ⛔ 404 = missing | ✅ on turn | ⛔ 403 |
| `POST /{id}/invitations` | ✅ while not finished, else 409 | ⛔ 403 | ⛔ 403 | ⛔ 404 = missing | ⛔ 403 | ⛔ 403 |
| `GET /{id}/invitations` | ✅ | ⛔ 403 | ⛔ 403 | ⛔ 404 = missing | ⛔ 403 | ⛔ 403 |
| `POST /{id}/cancel` | ✅ non-terminal | ⛔ 403 | ⛔ 403 | ⛔ 404 = missing | ⛔ 403 | ⛔ 403 |
| `GET /api/invitations/{token}` | preview for anyone holding the link | | | | | |
| `POST /api/invitations/{token}/guest-code` | e-mails a code **to the invited address**; the answer never contains it | | | | | |
| `POST /api/invitations/{token}/guest-session` | only with that code | | | | | |
| `POST /api/invitations/{token}/accept`, `/claim`, `/decline` | account whose e-mail = invited e-mail | | | ⛔ 403 EMAIL_MISMATCH | ⛔ 403 | ⛔ 403 |
| `POST /api/invitations/{id}/resend`, `/revoke` | ✅ (resend: not when finished, 409) | ⛔ 403 | ⛔ 403 | ⛔ 404 = unknown id | ⛔ 403 | ⛔ 403 |

## 3. What changed

| # | Was | Now |
|---|---|---|
| G1 | **The sender (or anyone the e-mail reached) could open the invitee's guest session with the link alone**, bind their own certificate to the slot and sign as the invitee; the audit named the invitee. Reproduced on a live server. | The link opens a session only with a **one-time 6-digit code e-mailed to the invited address** (10 minutes, single use, 5 wrong entries kill a code, 15 wrong entries lock the invitation until the owner re-sends it, one new code per minute, only its SHA-256 stored). Outside development the API also **no longer returns the raw link to the sender**. A production server refuses to start with `SIGNER_GUEST_VERIFICATION=false`. Re-running the attack now ends at 403. |
| G3 | A bound slot could be re-bound while DRAFT. | A slot's certificate is set once (409 `CERTIFICATE_ALREADY_BOUND`). |
| G4 | One certificate could hold several slots. | Refused at creation (400) and at binding (409). |
| G5 | 404 for a missing workflow, 403 for someone else's; sequential ids made that a probe. | Same 404 body for both; invitation ids likewise. Signers of a workflow still get 403 for owner-only actions (they know it exists). |
| G8 | Invitations could be created or re-sent for finished workflows. | 409 `INVALID_WORKFLOW_STATE`. |
| G9 | Owner claiming a turn got 409; owner-only checks used code `UNAUTHORIZED`. | 403 `WORKFLOW_NOT_ACCESSIBLE`. |

## 4. Still open (decisions for a later phase)

| # | Severity | Gap |
|---|---|---|
| G2 | High | No check that a certificate belongs to the invited person ("first card wins" for unbound slots). The e-mailed code now ensures the *person at the keyboard* reads the invitee's mailbox, but the certificate itself is not matched to them. Options: match the certificate's e-mail or subject, or let the sender pre-bind or approve. |
| G6 | Low (feature) | The owner cannot download the document unless it is their turn. |
| G7 | Low (privacy) | Every participant sees the full audit trail, including other signers' e-mail addresses. |
| — | Low | Workflow ids are still sequential (`WF-<n>`); harmless now that strangers get "not found", but random ids would remove the last hint. |

## 5. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SIGNER_GUEST_VERIFICATION` | `true` | Guest sessions need the e-mailed code. Production refuses `false`. |
| `SIGNER_INVITATION_EXPOSE_LINKS` | `false` (`true` in `dev`) | Return the invitee's raw link to the sender. |

Database: migration `V7__guest_verification_codes.sql` adds five columns to
`invitations` (additive, defaults 0 / NULL).

Desktop: "Review & Sign" now asks for the code ("Check your e-mail"), with
"Send a new code". Screens that showed returned links fall back to "invitation
e-mailed to …" when the server hides them.

## 6. Tests

- `AuthorizationMatrixHttpTests` (real HTTP, PostgreSQL): stranger = missing
  for every workflow route and invitation id; owner-not-signer cannot claim,
  download or sign; signer off turn cannot download; only the owner manages
  invitations and cancels; no invitations for finished workflows; one
  certificate per slot and per workflow; **the sender cannot open the
  invitee's session with the link** (G1 regression).
- `GuestVerificationCodeTests`: link alone refused; single use; bound to its
  invitation; 5 / 15 wrong-entry limits counted despite the error; expiry;
  resend throttle; only the hash stored, never logged; no codes for
  cancelled or replaced invitations.
- `InvitationLinkExposureTests`: outside dev the sender gets no URL.
- `GuestSigningSessionE2ETests`: full guest signing with the mock card, now
  through the code.
- `InvitationDialogCodeTests`, `ProductionSafetyValidatorTests`,
  `RefreshTokenMigrationLiveTests` (V7).
