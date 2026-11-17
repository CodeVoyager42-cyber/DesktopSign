import assert from "node:assert/strict";
import { test } from "node:test";
import {
  MAX_UPLOAD_FALLBACK_BYTES,
  documentNameFor,
  isEmail,
  looksLikePdf,
  problemWith,
  readableSize,
  signersFrom,
  uploadProblem,
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

function bytes(text: string): Uint8Array {
  return new Uint8Array([...text].map((c) => c.charCodeAt(0)));
}

test("the bytes decide whether it is a PDF, not the name", () => {
  assert.equal(looksLikePdf(bytes("%PDF-1.7")), true);
  assert.equal(looksLikePdf(bytes("This is a text file")), false);
  // Case matters: %pdf- is not the magic.
  assert.equal(looksLikePdf(bytes("%pdf-1.7")), false);
});

test("a file shorter than the magic is not a PDF, and does not throw", () => {
  assert.equal(looksLikePdf(bytes("%PD")), false);
  assert.equal(looksLikePdf(new Uint8Array()), false);
});

test("the size pre-check speaks in the reader's units and names both numbers", () => {
  const problem = uploadProblem({ name: "big.pdf", size: 150 * 1024 * 1024 },
    MAX_UPLOAD_FALLBACK_BYTES);
  assert.match(String(problem), /150\.0 MB/);
  assert.match(String(problem), /100\.0 MB/);
});

test("a file at exactly the ceiling is allowed through", () => {
  assert.equal(
    uploadProblem({ name: "ok.pdf", size: MAX_UPLOAD_FALLBACK_BYTES },
      MAX_UPLOAD_FALLBACK_BYTES),
    null,
  );
});

test("no file, the wrong extension and an empty file each say so", () => {
  assert.match(String(uploadProblem(null, MAX_UPLOAD_FALLBACK_BYTES)), /Choose the PDF/);
  assert.match(
    String(uploadProblem({ name: "notes.txt", size: 10 }, MAX_UPLOAD_FALLBACK_BYTES)),
    /Only PDF/,
  );
  assert.match(
    String(uploadProblem({ name: "empty.pdf", size: 0 }, MAX_UPLOAD_FALLBACK_BYTES)),
    /empty/,
  );
});

test("the fallback ceiling matches the shipped default", () => {
  // It is only used before GET /web/api/app-info answers; if it disagreed
  // with the server the screen would refuse files the server would accept.
  assert.equal(MAX_UPLOAD_FALLBACK_BYTES, 104857600);
});
