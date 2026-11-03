import assert from "node:assert/strict";
import { test } from "node:test";
import type { AuditEntry, Workflow } from "./api.ts";
import { at, buildTimeline, inStory } from "./timeline.ts";

/**
 * The status timeline, tested without a browser.
 *
 * Run with `npm test`, which is Node's own test runner — no test framework
 * added and no bundler involved, because Node runs the TypeScript directly.
 *
 * What is worth testing here is what the screen chooses to say: which of the
 * server's events reach the story, that nothing is dropped for being
 * unrecognised, and that the last row is the current state rather than the
 * last thing in the trail. Every call passes `"UTC"` as the zone so these
 * assertions do not depend on where the suite runs.
 */

function workflow(over: Partial<Workflow> = {}): Workflow {
  return {
    workflowId: "WF-1",
    documentName: "contract.pdf",
    status: "WAITING_FOR_SIGNER",
    currentRevision: 0,
    completedSignatures: 0,
    currentSignerUserId: "bob",
    currentSignerFingerprintShort: null,
    blockedReason: null,
    awaitingYourCertificate: false,
    ...over,
  };
}

function entry(sequence: number, eventType: string, over: Partial<AuditEntry> = {}): AuditEntry {
  return {
    sequence,
    eventType,
    actor: "bob",
    detail: "Revision 1 accepted (54123 bytes)",
    occurredAt: "2026-10-24T09:15:00Z",
    ...over,
  };
}

test("the story is what happened to the document, not the bookkeeping", () => {
  const rows = buildTimeline(
    workflow(),
    [
      entry(1, "WORKFLOW_CREATED"),
      entry(2, "CERTIFICATE_BOUND"),
      entry(3, "WORKFLOW_PREPARED"),
      entry(4, "REVISION_CREATED"),
      entry(5, "SIGNATURE_PLACEMENT_RECORDED"),
      entry(6, "SIGNER_COMPLETED"),
    ],
    "bob",
    "UTC",
  );

  assert.deepEqual(
    rows.filter((row) => row.story).map((row) => row.words),
    [
      "Uploaded",
      "Your certificate was registered",
      "Signed",
      "Waiting for your signature",
    ],
  );
  // Nothing is removed — the rest is still there for the full trail.
  assert.equal(rows.length, 7);
});

test("what went wrong is always in the story, and marked", () => {
  const rows = buildTimeline(
    workflow({ status: "WAITING_FOR_SIGNER" }),
    [entry(1, "WORKFLOW_CREATED"), entry(2, "SIGNATURE_VALIDATION_FAILED")],
    "bob",
    "UTC",
  );

  const failure = rows[1];
  assert.equal(failure.story, true);
  assert.equal(failure.mark, "blocked");
  assert.equal(failure.words, "A signature did not pass its check");
});

test("an event type this version has never heard of is shown, not dropped", () => {
  const unknown = entry(1, "WORKFLOW_FROZEN");

  assert.equal(inStory(unknown), true);
  const [row] = buildTimeline(workflow({ status: "COMPLETED" }), [unknown], "bob", "UTC");
  assert.equal(row.words, "Workflow frozen");
  assert.equal(row.mark, "done");
});

test("the server's detail never reaches a row", () => {
  const rows = buildTimeline(
    workflow({ status: "COMPLETED" }),
    [entry(1, "REVISION_CREATED")],
    "bob",
    "UTC",
  );

  // The detail of this event is written for whoever reads an audit trail;
  // the words are the website's own.
  assert.equal(rows[0].words, "A new version of the document was stored");
  assert.equal(
    JSON.stringify(rows).includes("54123"),
    false,
    "an audit detail must not travel into the timeline",
  );
});

test("the trail is read in sequence order, however it arrives", () => {
  const rows = buildTimeline(
    workflow({ status: "COMPLETED" }),
    [
      entry(3, "WORKFLOW_COMPLETED"),
      entry(1, "WORKFLOW_CREATED"),
      entry(2, "SIGNER_COMPLETED"),
    ],
    "bob",
    "UTC",
  );

  assert.deepEqual(rows.map((row) => row.key), ["1", "2", "3"]);
});

test("the last row is the current state, not the last event", () => {
  const rows = buildTimeline(
    workflow({ currentSignerUserId: "bob" }),
    [entry(1, "WORKFLOW_CREATED"), entry(2, "WORKFLOW_PREPARED")],
    "bob",
    "UTC",
  );

  const last = rows[rows.length - 1];
  assert.equal(last.words, "Waiting for your signature");
  assert.equal(last.mark, "pending");
  assert.equal(last.when, null);
  // Not something the server recorded, so a screen counting what it did
  // record must not count this row.
  assert.equal(last.recorded, false);
  assert.deepEqual(
    rows.filter((row) => row.recorded).map((row) => row.key),
    ["1", "2"],
  );
});

test("a draft waits for a certificate, not for a signature", () => {
  // Found against a live server: a new workflow is DRAFT with no
  // blockedReason, and the generic wording claimed the signer was being
  // asked to sign a document that had not been prepared.
  const rows = buildTimeline(
    workflow({ status: "DRAFT", currentSignerUserId: "bob" }),
    [entry(1, "WORKFLOW_CREATED"), entry(2, "CERTIFICATE_PENDING")],
    "bob",
    "UTC",
  );

  const last = rows[rows.length - 1];
  assert.equal(last.words, "Waiting for your certificate");
  assert.equal(last.mark, "pending");
  // And the reason is in the story rather than only in the trail.
  assert.equal(rows[1].story, true);
  assert.equal(
    rows[1].words,
    "A certificate must be registered before this is prepared",
  );
});

test("a document waiting for somebody else's signature names them", () => {
  // Only reachable for a workflow stored before SignFlow became
  // single-signer; the wording must still be true rather than claim it is
  // the reader's turn.
  const rows = buildTimeline(workflow({ currentSignerUserId: "carol" }), [], "bob", "UTC");

  assert.equal(rows[0].words, "Waiting for carol");
});

test("a blocked workflow ends on the server's own reason", () => {
  const rows = buildTimeline(
    workflow({
      status: "WAITING_FOR_SIGNER",
      blockedReason: "A certificate must be registered before the document is prepared",
    }),
    [entry(1, "WORKFLOW_CREATED")],
    "bob",
    "UTC",
  );

  const last = rows[rows.length - 1];
  assert.equal(last.mark, "blocked");
  assert.equal(
    last.words,
    "A certificate must be registered before the document is prepared",
  );
});

test("a document with nobody to sign it is not prepared yet", () => {
  const rows = buildTimeline(
    workflow({ status: "READY_FOR_SIGNING", currentSignerUserId: null }),
    [],
    "bob",
    "UTC",
  );

  assert.equal(rows[0].words, "Not prepared yet");
});

test("nothing follows the last event once it is finished", () => {
  for (const status of ["COMPLETED", "CANCELLED", "FAILED"]) {
    const rows = buildTimeline(
      workflow({ status }),
      [entry(1, "WORKFLOW_CREATED")],
      "bob",
      "UTC",
    );
    assert.equal(rows.length, 1, status);
    assert.equal(rows[0].key, "1", status);
  }
});

test("the date is written once per day, and the time on every row", () => {
  const rows = buildTimeline(
    workflow({ status: "COMPLETED" }),
    [
      entry(1, "WORKFLOW_CREATED", { occurredAt: "2026-10-24T09:15:00Z" }),
      entry(2, "SIGNER_COMPLETED", { occurredAt: "2026-10-24T16:40:00Z" }),
      entry(3, "WORKFLOW_COMPLETED", { occurredAt: "2026-10-25T08:05:00Z" }),
    ],
    "bob",
    "UTC",
  );

  assert.deepEqual(rows.map((row) => row.when), [
    "24 October 2026 at 09:15",
    "16:40",
    "25 October 2026 at 08:05",
  ]);
});

test("a timestamp that cannot be read leaves the row without one", () => {
  assert.equal(at(null, "UTC"), null);
  assert.equal(at("not a date", "UTC"), null);

  const [row] = buildTimeline(
    workflow({ status: "COMPLETED" }),
    [entry(1, "WORKFLOW_CREATED", { occurredAt: "not a date" })],
    "bob",
    "UTC",
  );
  assert.equal(row.when, null);
  assert.equal(row.words, "Uploaded");
});
