import assert from "node:assert/strict";
import { test } from "node:test";
import type { Task, Workflow } from "./api.ts";
import { FINISHED, STATE_WORDS, groupRows, mergeRows, rowStory } from "./dashboard.ts";

/**
 * The dashboard's grouping, tested without a browser.
 *
 * Run with `npm test`, which is Node's own test runner — no test framework
 * added, and no bundler involved, because Node runs the TypeScript directly.
 * What is worth testing here is the merge: which group a document lands in
 * depends on the viewer's own slot, and a document can arrive in both lists.
 */

function workflow(over: Partial<Workflow> = {}): Workflow {
  return {
    workflowId: "WF-1",
    documentName: "contract.pdf",
    status: "WAITING_FOR_SIGNER",
    currentRevision: 0,
    completedSignatures: 0,
    totalSignatures: 2,
    currentSignerUserId: null,
    currentSignerFingerprintShort: null,
    blockedReason: null,
    signingPolicy: "SEQUENTIAL",
    waitingForClaim: false,
    awaitingYourCertificate: false,
    ...over,
  };
}

function task(over: Partial<Task> = {}): Task {
  return {
    workflowId: "WF-1",
    documentName: "contract.pdf",
    senderId: "sender",
    senderDisplayName: "Sender Name",
    status: "WAITING_FOR_SIGNER",
    createdAt: null,
    completedAt: null,
    myState: {
      myStateLabel: "WAITING_FOR_OTHERS",
      participantOrder: 2,
      signed: false,
      waitingForMe: false,
      waitingForClaim: false,
    },
    totalSignatures: 2,
    completedSignatures: 1,
    signingPolicy: "SEQUENTIAL",
    participants: null,
    expiresAt: null,
    updatedAt: null,
    ...over,
  };
}

test("a draft is waiting for certificates, not unsent", () => {
  // Invitations go out when the workflow is created; DRAFT only means a slot
  // still has no certificate bound. "Not sent yet" against a document whose
  // invitations have gone would invite a pointless resend.
  assert.equal(STATE_WORDS.DRAFT, "Waiting for certificates");
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

test("a document appears once even when it is both sent and signed by you", () => {
  const rows = mergeRows(
    [workflow({ workflowId: "WF-7" })],
    [task({ workflowId: "WF-7" })],
    "me",
  );

  assert.equal(rows.length, 1);
  // The participating entry wins, because it is the one that knows the
  // viewer's own slot — but it is still marked as something they sent.
  assert.equal(rows[0].sent, true);
  assert.equal(rows[0].signed, 1);
});

test("the viewer's own slot decides the group, not the document's state", () => {
  const waitingForMe = task({
    workflowId: "WF-MINE",
    myState: { ...task().myState!, waitingForMe: true },
  });
  const waitingForThem = task({ workflowId: "WF-THEIRS" });

  const { mine, theirs, finished } = groupRows(
    mergeRows([], [waitingForMe, waitingForThem], "me"),
  );

  assert.deepEqual(mine.map((r) => r.id), ["WF-MINE"]);
  assert.deepEqual(theirs.map((r) => r.id), ["WF-THEIRS"]);
  assert.equal(finished.length, 0);
});

test("a slot that only needs claiming is still action required", () => {
  // FLEXIBLE workflows: nobody holds the turn until somebody claims it, and
  // the viewer is one of the people who may.
  const rows = mergeRows([], [task({
    myState: { ...task().myState!, waitingForClaim: true },
  })], "me");

  assert.equal(groupRows(rows).mine.length, 1);
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

test("the row's line says who it is from, or that you sent it", () => {
  const sent = mergeRows([workflow()], [], "me")[0];
  const received = mergeRows([], [task()], "me")[0];

  assert.equal(rowStory(sent), "you sent this · 0 of 2 signed");
  assert.equal(rowStory(received), "from Sender Name · 1 of 2 signed");
});

test("a sender with no display name falls back to the id", () => {
  const row = mergeRows([], [task({ senderDisplayName: null })], "me")[0];

  assert.equal(rowStory(row), "from sender · 1 of 2 signed");
});
