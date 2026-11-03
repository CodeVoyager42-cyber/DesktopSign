import type { Task, Workflow } from "./api";

/**
 * Turning the document lists into dashboard rows.
 *
 * In its own module rather than in the page, for two reasons: a page module
 * may only export the names Next.js knows about, and this is the part worth
 * testing without a browser in the room.
 *
 * Every document belongs to one person, who is also its signer, so a row
 * never describes anybody else's part in it.
 */

// The raw state names are the server's — it deliberately sends no
// presentation strings — so the mapping to something readable lives here.
export const STATE_WORDS: Record<string, string> = {
  // DRAFT is not "not uploaded yet": the PDF is on the server, and the
  // workflow stays DRAFT only until a certificate is registered for it —
  // which the signer does from the machine their token is plugged into.
  DRAFT: "Waiting for your certificate",
  READY_FOR_SIGNING: "Ready to sign",
  WAITING_FOR_SIGNER: "Ready to sign",
  SIGNATURE_SUBMITTED: "Checking your signature",
  VALIDATING: "Checking your signature",
  COMPLETED: "Signed",
  CANCELLED: "Cancelled",
  FAILED: "Stopped by an error",
};

export const FINISHED = ["COMPLETED", "CANCELLED", "FAILED"];

export interface Row {
  id: string;
  name: string;
  status: string;
  signed: boolean;
  waitingForMe: boolean;
}

/**
 * One row per document, merged from the two lists.
 *
 * The same document appears in both, so the participating entry is applied
 * second and wins: it is the one that knows about the signer's own slot.
 * Keyed by workflow id, which is why a document cannot appear twice.
 */
export function mergeRows(
  owned: Workflow[],
  tasks: Task[],
  username: string,
): Row[] {
  const rows = new Map<string, Row>();
  for (const w of owned) {
    rows.set(w.workflowId, {
      id: w.workflowId,
      name: w.documentName,
      status: w.status,
      signed: w.completedSignatures > 0,
      waitingForMe: w.currentSignerUserId === username,
    });
  }
  for (const t of tasks) {
    rows.set(t.workflowId, {
      id: t.workflowId,
      name: t.documentName,
      status: t.status,
      signed: Boolean(t.myState?.signed),
      waitingForMe: Boolean(t.myState?.waitingForMe),
    });
  }
  return [...rows.values()];
}

/** The second line of a row: where the document has got to. */
export function rowStory(row: Row): string {
  if (row.signed) {
    return "you signed this";
  }
  return STATE_WORDS[row.status] ?? row.status;
}

/** The two groups, in the order they are shown. */
export function groupRows(rows: Row[]) {
  const finished = rows.filter((row) => FINISHED.includes(row.status));
  const live = rows.filter((row) => !FINISHED.includes(row.status));
  return {
    mine: live.filter((row) => row.waitingForMe),
    theirs: live.filter((row) => !row.waitingForMe),
    finished,
  };
}
