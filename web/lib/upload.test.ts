import assert from "node:assert/strict";
import { test } from "node:test";
import { documentNameFor, problemWith } from "./upload.ts";

/**
 * What the upload form can judge for itself, tested without a browser.
 *
 * Run with `npm test`, which is Node's own test runner. Everything the
 * server decides — the size limit, whether the bytes really are a PDF — is
 * deliberately not duplicated here; see `upload.ts`.
 */

/** A File stand-in: only `name` is read by either function. */
function file(name: string): File {
  return { name } as File;
}

test("the name is defaulted from the file, extension and all", () => {
  // The extension is kept: this name is what the signed PDF downloads as.
  assert.equal(documentNameFor("contract.pdf"), "contract.pdf");
});

test("a path from a file input is reduced to its name", () => {
  assert.equal(documentNameFor("C:\\Users\\me\\contract.pdf"), "contract.pdf");
  assert.equal(documentNameFor("/home/me/contract.pdf"), "contract.pdf");
});

test("surrounding whitespace is dropped", () => {
  assert.equal(documentNameFor("  contract.pdf  "), "contract.pdf");
});

test("the first thing missing is the only thing said", () => {
  assert.equal(problemWith(null, ""), "Choose the PDF you want to sign.");
  assert.equal(
    problemWith(file("notes.txt"), "notes.txt"),
    "Only PDF documents can be signed.",
  );
  assert.equal(problemWith(file("contract.pdf"), "  "), "Give the document a name.");
});

test("a named PDF has nothing wrong with it", () => {
  assert.equal(problemWith(file("contract.pdf"), "contract.pdf"), null);
});

test("the extension check ignores case", () => {
  assert.equal(problemWith(file("CONTRACT.PDF"), "CONTRACT.PDF"), null);
});
