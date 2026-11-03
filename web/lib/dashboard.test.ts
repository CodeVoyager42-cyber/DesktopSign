import assert from "node:assert/strict";
import { test } from "node:test";
import type { Task, Workflow } from "./api.ts";
import { FINISHED, STATE_WORDS, groupRows, mergeRows, rowStory } from "./dashboard.ts";

/**
 * The dashboard's grouping, tested without a browser.
 *
 * Run with `npm test`, which is Node's own test runner — no test framework
 * added, and no bundler involved, because Node runs the TypeScript directly.
 * What is worth testing here is the merge: whether a document is waiting for
 * its signer comes from the slot state, and the same document arrives in
 * both lists.
 */

function workflow(over: Partial<Workflow> = {}): Workflow {
  return {
    workflowId: "WF-1",
    documentName: "contract.pdf",
    status: "WAITING_FOR_SIGNER",
    currentRevision: 0,
    completedSignatures: 0,
    currentSignerUserId: null,
    currentSignerFingerprintShort: null,
    blockedReason: null,
    awaitingYourCertificate: false,
    ...over,
  };
}

function task(over: Partial<Task> = {}): Task {
  return {
    workflowId: "WF-1",
    documentName: "contract.pdf",
    senderId: "me",
    senderDisplayName: "My Name",
    status: "WAITING_FOR_SIGNER",
    createdAt: null,
    completedAt: null,
    myState: {
      myStateLabel: "IN_PROGRESS",
      participantOrder: 1,
      signed: false,
      waitingForMe: false,
    },
    updatedAt: null,
    ...over,
  };
}

test("a draft is waiting for a certificate, not unsent", () => {
  // The PDF is already on the server; DRAFT only means no certificate is
  // registered for it yet. "Not uploaded yet" would be a lie.
  assert.equal(STATE_WORDS.DRAFT, "Waiting for your certificate");
});

test("a cancelled document is finished, so it has a group to live in", () => {
  // The server used to leave CANCELLED out of the owner's list, which meant
  // this grouping could never receive one. It is in the list now.
  assert.equal(FINISHED.includes("CANCELLED"), true);
  const { finished } = groupRows(
    mergeRows([workflow({ status: "CANCELLED" })], [], "alice"),
  );
  assert.deepEqual(finished.map((row) => row.status), ["CANCELLED"]);
});

test("a document appears once even though it is in both lists", () => {
  const rows = mergeRows(
    [workflow({ workflowId: "WF-7" })],
    [task({ workflowId: "WF-7", myState: { ...task().myState!, signed: true } })],
    "me",
  );

  assert.equal(rows.length, 1);
  // The participating entry wins, because it is the one that knows the
  // signer's own slot.
  assert.equal(rows[0].signed, true);
});

test("the signer's own slot decides the group, not the document's state", () => {
  const waitingForMe = task({
    workflowId: "WF-MINE",
    myState: { ...task().myState!, waitingForMe: true },
  });
  const beingPrepared = task({ workflowId: "WF-PREP" });

  const { mine, theirs, finished } = groupRows(
    mergeRows([], [waitingForMe, beingPrepared], "me"),
  );

  assert.deepEqual(mine.map((r) => r.id), ["WF-MINE"]);
  assert.deepEqual(theirs.map((r) => r.id), ["WF-PREP"]);
  assert.equal(finished.length, 0);
});

test("an owner who holds the current slot is action required too", () => {
  // The owner list has no slot state of its own, so this comes from the
  // server naming them as the current signer.
  const rows = mergeRows(
    [workflow({ currentSignerUserId: "me" })],
    [],
    "me",
  );

  assert.equal(groupRows(rows).mine.length, 1);
});

test("cancelled and failed are finished, not in flight", () => {
  const rows = mergeRows(
    [
      workflow({ workflowId: "WF-C", status: "CANCELLED" }),
      workflow({ workflowId: "WF-F", status: "FAILED" }),
      workflow({ workflowId: "WF-D", status: "COMPLETED" }),
    ],
    [],
    "me",
  );

  const { mine, theirs, finished } = groupRows(rows);
  assert.equal(mine.length, 0);
  assert.equal(theirs.length, 0);
  assert.deepEqual(finished.map((r) => r.id), ["WF-C", "WF-F", "WF-D"]);
});

test("the row's line says where the document has got to", () => {
  const waiting = mergeRows([workflow()], [], "me")[0];
  const signed = mergeRows(
    [],
    [task({ myState: { ...task().myState!, signed: true } })],
    "me",
  )[0];

  assert.equal(rowStory(waiting), "Ready to sign");
  assert.equal(rowStory(signed), "you signed this");
});

test("an unrecognised state is shown as it came, not as a blank", () => {
  const row = mergeRows([workflow({ status: "SOMETHING_NEW" })], [], "me")[0];

  assert.equal(rowStory(row), "SOMETHING_NEW");
});
