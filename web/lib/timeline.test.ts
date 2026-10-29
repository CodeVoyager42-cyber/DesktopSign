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
    currentRevision: 1,
    completedSignatures: 1,
    totalSignatures: 2,
    currentSignerUserId: "bob",
    currentSignerFingerprintShort: null,
    blockedReason: null,
    signingPolicy: "SEQUENTIAL",
    waitingForClaim: false,
    awaitingYourCertificate: false,
    ...over,
  };
}

function entry(sequence: number, eventType: string, over: Partial<AuditEntry> = {}): AuditEntry {
  return {
    sequence,
    eventType,
    actor: "alice",
    detail: "Invitation created for signer 2 (bob@example.com)",
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
      entry(3, "INVITATION_SENT"),
      entry(4, "REVISION_CREATED"),
      entry(5, "SIGNATURE_PLACEMENT_RECORDED"),
      entry(6, "SIGNER_COMPLETED"),
    ],
    "alice",
    "UTC",
  );

  assert.deepEqual(
    rows.filter((row) => row.story).map((row) => row.words),
    ["Created", "The invitation went out", "Signed", "Waiting for bob"],
  );
  // Nothing is removed — the rest is still there for the full trail.
  assert.equal(rows.length, 7);
});

test("what went wrong is always in the story, and marked", () => {
  const rows = buildTimeline(
    workflow({ status: "WAITING_FOR_SIGNER" }),
    [entry(1, "WORKFLOW_CREATED"), entry(2, "SIGNATURE_VALIDATION_FAILED")],
    "alice",
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
  const [row] = buildTimeline(workflow({ status: "COMPLETED" }), [unknown], "alice", "UTC");
  assert.equal(row.words, "Workflow frozen");
  assert.equal(row.mark, "done");
});

test("the server's detail never reaches a row", () => {
  const rows = buildTimeline(
    workflow({ status: "COMPLETED" }),
    [entry(1, "INVITATION_CREATED")],
    "alice",
    "UTC",
  );

  // The detail of this event carries the invited address; the words are the
  // website's own.
  assert.equal(rows[0].words, "A signer was invited");
  assert.equal(
    JSON.stringify(rows).includes("bob@example.com"),
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
    "alice",
    "UTC",
  );

  assert.deepEqual(rows.map((row) => row.key), ["1", "2", "3"]);
});

test("the last row is who is being waited for now, not the last event", () => {
  // The trail says bob was activated and then gave the turn back; the
  // workflow says it is carol's. The screen must say carol.
  const rows = buildTimeline(
    workflow({ currentSignerUserId: "carol" }),
    [entry(1, "NEXT_SIGNER_ACTIVATED", { actor: "bob" }), entry(2, "SIGNING_TURN_RELEASED")],
    "alice",
    "UTC",
  );

  const last = rows[rows.length - 1];
  assert.equal(last.words, "Waiting for carol");
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
    workflow({ status: "DRAFT", currentSignerUserId: "bob", completedSignatures: 0 }),
    [entry(1, "WORKFLOW_CREATED"), entry(2, "CERTIFICATES_PENDING")],
    "alice",
    "UTC",
  );

  const last = rows[rows.length - 1];
  assert.equal(last.words, "Waiting for a certificate from bob");
  assert.equal(last.mark, "pending");
  // And the reason is in the story rather than only in the trail.
  assert.equal(rows[1].story, true);
  assert.equal(
    rows[1].words,
    "Every signer must register a certificate before this is prepared",
  );
});

test("a draft waiting on the reader says so in the second person", () => {
  const rows = buildTimeline(
    workflow({ status: "DRAFT", currentSignerUserId: "bob" }),
    [],
    "bob",
    "UTC",
  );
  assert.equal(rows[0].words, "Waiting for your certificate");
});

test("a draft with nobody named waits for the signers", () => {
  const rows = buildTimeline(
    workflow({ status: "DRAFT", currentSignerUserId: null }),
    [],
    "alice",
    "UTC",
  );
  assert.equal(rows[0].words, "Waiting for the signers' certificates");
});

test("it says so when the wait is yours", () => {
  const rows = buildTimeline(workflow({ currentSignerUserId: "bob" }), [], "bob", "UTC");

  assert.equal(rows[0].words, "Waiting for you");
});

test("a blocked workflow ends on the server's own reason", () => {
  const rows = buildTimeline(
    workflow({
      status: "WAITING_FOR_SIGNER",
      blockedReason: "1 signer(s) must bind a certificate before the document is prepared",
    }),
    [entry(1, "WORKFLOW_CREATED")],
    "alice",
    "UTC",
  );

  const last = rows[rows.length - 1];
  assert.equal(last.mark, "blocked");
  assert.equal(
    last.words,
    "1 signer(s) must bind a certificate before the document is prepared",
  );
});

test("a flexible turn nobody has claimed is waiting for whoever takes it", () => {
  const rows = buildTimeline(
    workflow({ signingPolicy: "FLEXIBLE", waitingForClaim: true, currentSignerUserId: null }),
    [],
    "alice",
    "UTC",
  );

  assert.equal(rows[0].words, "Waiting for one of the signers to take the next turn");
});

test("nothing follows the last event once it is finished", () => {
  for (const status of ["COMPLETED", "CANCELLED", "FAILED"]) {
    const rows = buildTimeline(
      workflow({ status }),
      [entry(1, "WORKFLOW_CREATED")],
      "alice",
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
    "alice",
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
    "alice",
    "UTC",
  );
  assert.equal(row.when, null);
  assert.equal(row.words, "Created");
});
