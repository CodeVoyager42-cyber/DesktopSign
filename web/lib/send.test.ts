import assert from "node:assert/strict";
import { test } from "node:test";
import {
  coSignerProblem,
  documentNameFor,
  isEmail,
  problemWith,
  readableSize,
  signersFrom,
} from "./send.ts";

/**
 * The sending side, tested without a browser.
 *
 * What is worth testing here is the slots: blank rows dropped and the
 * numbering closed up after them, because an order with a hole in it is a
 * workflow the server will refuse for a reason the reader cannot see.
 */

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

test("an address is the shape the server refuses, a plain name an account", () => {
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

test("a file size reads the way a reader glances at it", () => {
  assert.equal(readableSize(0), "1 KB");
  assert.equal(readableSize(820 * 1024), "820 KB");
  assert.equal(readableSize(1024 * 1024), "1.0 MB");
  assert.equal(readableSize(1.4 * 1024 * 1024), "1.4 MB");
  assert.equal(readableSize(12 * 1024 * 1024), "12.0 MB");
});

test("an e-mail in a signer box is caught before the server sees it", () => {
  // The mistake somebody makes who remembers the old e-mail invitations.
  const problem = coSignerProblem(["alice", "bob@example.com"]);
  assert.match(String(problem), /e-mail address/);
  assert.match(String(problem), /username/);
});

test("the same signer twice is refused, case-insensitively", () => {
  assert.match(String(coSignerProblem(["alice", "bob", "Bob"])), /named twice/);
  assert.equal(coSignerProblem(["alice", "bob", "charlie"]), null);
});

test("blank rows are ignored rather than reported", () => {
  // The reader is still filling the form in; an empty row is not a mistake.
  assert.equal(coSignerProblem(["alice", "  ", ""]), null);
});

test("whether the account exists is deliberately not checked here", () => {
  // That is the server’s judgement, and it answers with its own message.
  assert.equal(coSignerProblem(["alice", "nobody-with-this-name"]), null);
});
