import type { Task, Workflow } from "./api";

/**
 * Turning the two workflow lists into dashboard rows.
 *
 * In its own module rather than in the page, for two reasons: a page module
 * may only export the names Next.js knows about, and this is the part worth
 * testing without a browser in the room.
 */

// The raw state names are the server's — it deliberately sends no
// presentation strings — so the mapping to something readable lives here.
export const STATE_WORDS: Record<string, string> = {
  DRAFT: "Not sent yet",
  READY_FOR_SIGNING: "Ready to sign",
  WAITING_FOR_SIGNER: "Waiting for a signature",
  SIGNATURE_SUBMITTED: "Checking a signature",
  VALIDATING: "Checking a signature",
  COMPLETED: "Signed by everyone",
  CANCELLED: "Cancelled",
  FAILED: "Stopped by an error",
};

export const FINISHED = ["COMPLETED", "CANCELLED", "FAILED"];

export interface Row {
  id: string;
  name: string;
  status: string;
  signed: number;
  total: number;
  sent: boolean;
  from?: string;
  waitingForMe: boolean;
}

/**
 * One row per document, merged from the two lists.
 *
 * A workflow you sent AND sign appears in both, so the participating entry is
 * applied second and wins: it is the one that knows about your own slot.
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
      signed: w.completedSignatures,
      total: w.totalSignatures,
      sent: true,
      // The owner may hold a slot too, in which case the server already
      // names them as the current signer.
      waitingForMe: w.currentSignerUserId === username,
    });
  }
  for (const t of tasks) {
    const existing = rows.get(t.workflowId);
    rows.set(t.workflowId, {
      id: t.workflowId,
      name: t.documentName,
      status: t.status,
      signed: t.completedSignatures,
      total: t.totalSignatures,
      sent: Boolean(existing),
      from: t.senderDisplayName ?? t.senderId ?? undefined,
      waitingForMe: Boolean(
        t.myState && (t.myState.waitingForMe || t.myState.waitingForClaim),
      ),
    });
  }
  return [...rows.values()];
}

/** The second line of a row: who it is from, and how far it has got. */
export function rowStory(row: Row): string {
  const progress = `${row.signed} of ${row.total} signed`;
  if (row.sent || !row.from) {
    return `you sent this · ${progress}`;
  }
  return `from ${row.from} · ${progress}`;
}

/** The three groups, in the order they are shown. */
export function groupRows(rows: Row[]) {
  const finished = rows.filter((row) => FINISHED.includes(row.status));
  const live = rows.filter((row) => !FINISHED.includes(row.status));
  return {
    mine: live.filter((row) => row.waitingForMe),
    theirs: live.filter((row) => !row.waitingForMe),
    finished,
  };
}
