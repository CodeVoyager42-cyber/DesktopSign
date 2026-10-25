import assert from "node:assert/strict";
import { test } from "node:test";
import type { InvitationStatus } from "./api.ts";
import {
  canResend,
  canWithdraw,
  documentNameFor,
  isEmail,
  problemWith,
  signersFrom,
  slotNeedsAttention,
  slotStory,
} from "./send.ts";

/**
 * The sending side, tested without a browser.
 *
 * Two things are worth testing here. The slots: blank rows dropped and the
 * numbering closed up after them, because an order with a hole in it is a
 * workflow the server will refuse for a reason the reader cannot see. And the
 * reading back: which words a slot's row gets, and which actions it offers —
 * a *Withdraw* button beside a signature would be the worst of the mistakes
 * available here.
 */

function slot(over: Partial<InvitationStatus> = {}): InvitationStatus {
  return {
    invitationId: 7,
    signerOrder: 1,
    email: "bob@example.com",
    status: "PENDING",
    emailStatus: "SENT",
    createdAt: "2026-10-24T09:15:00Z",
    expiresAt: "2026-10-31T09:15:00Z",
    acceptedAt: null,
    slotSigned: false,
    ...over,
  };
}

/** A File without a browser: Node has had the class since 20. */
function pdf(name = "contract.pdf"): File {
  return new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], name, {
    type: "application/pdf",
  });
}

test("blank rows are dropped and the numbering closes up after them", () => {
  assert.deepEqual(signersFrom(["alice", "", "  ", "bob@example.com"]), [
    { order: 1, userId: "alice", certificateSha256: null },
    { order: 2, userId: "bob@example.com", certificateSha256: null },
  ]);
});

test("a slot is trimmed, because a trailing space makes it another person", () => {
  assert.deepEqual(signersFrom(["  bob@example.com  "]), [
    { order: 1, userId: "bob@example.com", certificateSha256: null },
  ]);
});

test("no certificate is ever named from the browser", () => {
  for (const spec of signersFrom(["alice", "bob@example.com"])) {
    assert.equal(spec.certificateSha256, null);
  }
});

test("an address is read as an invitation, a plain name as an account", () => {
  assert.equal(isEmail("bob@example.com"), true);
  assert.equal(isEmail("bob"), false);
});

test("the document is named after the file, extension and all", () => {
  // The name becomes the downloaded filename, so dropping ".pdf" would hand
  // everyone a file their system does not recognise.
  assert.equal(documentNameFor("Q3 report (final).pdf"), "Q3 report (final).pdf");
  assert.equal(documentNameFor("C:\\Users\\mnl\\contract.pdf"), "contract.pdf");
  assert.equal(documentNameFor("  spaced.pdf  "), "spaced.pdf");
});

test("the form says the one thing that is missing, in order", () => {
  assert.equal(problemWith(null, "", []), "Choose the PDF you want signed.");
  assert.equal(
    problemWith(pdf("notes.txt"), "notes.txt", ["alice"]),
    "Only PDF documents can be sent for signing.",
  );
  assert.equal(problemWith(pdf(), "   ", ["alice"]), "Give the document a name.");
  assert.equal(
    problemWith(pdf(), "contract.pdf", ["", "  "]),
    "Name at least one person to sign it.",
  );
  assert.equal(problemWith(pdf(), "contract.pdf", ["alice"]), null);
});

test("a signed slot reads as signed, whatever its invitation still says", () => {
  // The invitation row stays PENDING after signing; saying "Invited" against
  // a signature would be wrong in the way that matters.
  assert.equal(slotStory(slot({ slotSigned: true, status: "PENDING" })), "Signed");
  assert.equal(slotNeedsAttention(slot({ slotSigned: true, emailStatus: "FAILED" })), false);
});

test("an undelivered invitation says so rather than claiming it was sent", () => {
  const failed = slot({ emailStatus: "FAILED" });
  assert.equal(slotStory(failed), "Invited, but the e-mail could not be delivered");
  assert.equal(slotNeedsAttention(failed), true);
  // Resending is the way out of it.
  assert.equal(canResend(failed), true);
});

test("the ordinary states get their own words", () => {
  assert.equal(slotStory(slot()), "Invited");
  assert.equal(slotStory(slot({ emailStatus: "PENDING" })), "Invited, e-mail on its way");
  assert.equal(slotStory(slot({ status: "ACCEPTED", acceptedAt: "x" })), "Opened");
  assert.equal(slotStory(slot({ status: "REVOKED" })), "Withdrawn");
  assert.equal(slotStory(slot({ status: "EXPIRED" })), "The invitation expired");
  // An unrecognised state is shown as it came rather than hidden.
  assert.equal(slotStory(slot({ status: "SOMETHING_NEW" })), "SOMETHING_NEW");
});

test("nothing is offered against a signature", () => {
  const signed = slot({ slotSigned: true });
  assert.equal(canResend(signed), false);
  assert.equal(canWithdraw(signed), false);
});

test("a dead token can be replaced but not withdrawn again", () => {
  const expired = slot({ status: "EXPIRED" });
  assert.equal(canResend(expired), true);
  assert.equal(canWithdraw(expired), false);

  const revoked = slot({ status: "REVOKED" });
  assert.equal(canResend(revoked), false);
  assert.equal(canWithdraw(revoked), false);
});

test("a slot that was never invited offers neither", () => {
  // A slot named by username has no invitation row at all.
  const direct = slot({ invitationId: null, email: null, status: "UNKNOWN" });
  assert.equal(canResend(direct), false);
  assert.equal(canWithdraw(direct), false);
});
