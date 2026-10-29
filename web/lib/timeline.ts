import type { AuditEntry, Workflow } from "./api";
import { FINISHED } from "./dashboard.ts";

/**
 * Turning one workflow's audit trail into the status timeline.
 *
 * In its own module for the reasons `dashboard.ts` is: a page module may only
 * export the names Next.js knows about, and this is the part worth testing
 * without a browser in the room.
 *
 * The screen reads the server's own audit events rather than inferring a
 * history from the current state, which is why it can state the ORDER of what
 * happened instead of guessing it. Two consequences shape everything here:
 *
 *  1. The trail is complete, and it is internal. It records certificate
 *     bindings, revision writes, placement records and ignored duplicate
 *     submissions — bookkeeping that is right for an audit trail and would
 *     bury the story of a document under twenty rows of it. So there are two
 *     layers: the story, which is what happened to the agreement, and the
 *     full trail behind a disclosure, which is everything that was recorded.
 *
 *  2. `detail` is not rendered, anywhere. It is written for whoever reads an
 *     audit trail — "Revision 3 accepted (54123 bytes)", a short certificate
 *     fingerprint — and one of those strings carries the invited e-mail
 *     address of a slot (`INVITATION_CREATED`), which every participant can
 *     read this endpoint for. No screen in this product renders it today, and
 *     this one does not become the first. The words below are written here
 *     instead, from the event type, which is the part that means something to
 *     a reader.
 */

/** Where a row's dot sits in the story: finished, still to come, or wrong. */
export type Mark = "done" | "pending" | "blocked";

/**
 * Every event type the server can record, in the reader's language.
 *
 * The server deliberately sends no presentation strings, so this mapping is
 * the website's to keep — the same division as `STATE_WORDS` in
 * `dashboard.ts`. It mirrors `AuditEventType` in the server; an event type
 * missing from here is handled by {@link words}, not dropped.
 */
export const EVENT_WORDS: Record<string, string> = {
  WORKFLOW_CREATED: "Created",
  CERTIFICATES_PENDING: "Every signer must register a certificate before this is prepared",
  WORKFLOW_PREPARED: "Prepared for signing",
  INVITATION_CREATED: "A signer was invited",
  INVITATION_SENT: "The invitation went out",
  INVITATION_ACCEPTED: "The invitation was opened",
  INVITATION_REVOKED: "An invitation was withdrawn",
  INVITATION_EXPIRED: "An invitation expired",
  EMAIL_DELIVERY_FAILED: "An invitation e-mail could not be delivered",
  SIGNER_REGISTERED: "A signer created their account",
  CERTIFICATE_BOUND: "A signer's certificate was registered",
  SIGNING_SESSION_CREATED: "A signing session was opened",
  SIGNING_TURN_CLAIMED: "Took the next turn",
  SIGNING_TURN_RELEASED: "Gave the turn back",
  SIGNATURE_SUBMITTED: "A signature arrived",
  SIGNATURE_PLACEMENT_RECORDED: "Where the signature goes was recorded",
  SIGNATURE_VALIDATED: "The signature was checked",
  SIGNATURE_VALIDATION_FAILED: "A signature did not pass its check",
  REVISION_CREATED: "A new version of the document was stored",
  SIGNER_COMPLETED: "Signed",
  NEXT_SIGNER_ACTIVATED: "Passed to the next signer",
  WORKFLOW_COMPLETED: "Signed by everyone",
  STALE_REVISION_REJECTED: "A signature of an out-of-date version was refused",
  CERTIFICATE_MISMATCH_REJECTED: "A signature with the wrong certificate was refused",
  DUPLICATE_OPERATION_IGNORED: "A repeated submission was ignored",
  WORKFLOW_CANCELLED: "Cancelled",
  WORKFLOW_BLOCKED: "Stopped, and waiting",
  DESKTOP_SESSION_OPENED: "SignFlow was opened on a computer",
  DESKTOP_SESSION_REVOKED: "A desktop session was revoked",
};

/**
 * The events that went wrong. Their dot is `--warn`, and they are always part
 * of the story: a refusal nobody is shown is the one thing a timeline must
 * not do.
 */
const BLOCKED = new Set([
  "INVITATION_REVOKED",
  "INVITATION_EXPIRED",
  "EMAIL_DELIVERY_FAILED",
  "SIGNATURE_VALIDATION_FAILED",
  "STALE_REVISION_REJECTED",
  "CERTIFICATE_MISMATCH_REJECTED",
  "WORKFLOW_CANCELLED",
  "WORKFLOW_BLOCKED",
  "DESKTOP_SESSION_REVOKED",
]);

/**
 * What happened to the agreement, as opposed to what the server had to do
 * about it: created, invited, opened, signed, finished. Everything that went
 * wrong joins this set in {@link inStory}, and so does an event type this
 * version has never heard of — see there.
 */
const STORY = new Set([
  "WORKFLOW_CREATED",
  // Why a new document sits there doing nothing is exactly what its sender
  // comes to this screen to find out.
  "CERTIFICATES_PENDING",
  "INVITATION_SENT",
  "INVITATION_ACCEPTED",
  "SIGNER_COMPLETED",
  "WORKFLOW_COMPLETED",
]);

export interface TimelineRow {
  /** Stable across reloads: the audit sequence, or the synthetic last row. */
  key: string;
  words: string;
  /** Who did it, when the server named somebody. */
  who: string | null;
  /** Already formatted; null when the row is not an event that happened. */
  when: string | null;
  mark: Mark;
  /** Whether the row belongs to the story, or only to the full trail. */
  story: boolean;
  /**
   * Whether this row is an event the server recorded. False for the pending
   * row, which is read from the current state — so a screen offering "
   * everything that was recorded" can count and list the rows that were.
   */
  recorded: boolean;
}

/**
 * An event type with no words of its own, made readable:
 * `WORKFLOW_FROZEN` becomes "Workflow frozen".
 *
 * The server can grow a new event type without this file changing, and the
 * alternative — dropping what we do not recognise — would make the screen
 * quietly less than the trail it claims to show.
 */
function humanize(eventType: string): string {
  const spaced = eventType.toLowerCase().replace(/_/g, " ");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function words(entry: AuditEntry): string {
  return EVENT_WORDS[entry.eventType] ?? humanize(entry.eventType);
}

export function markOf(entry: AuditEntry): Mark {
  return BLOCKED.has(entry.eventType) ? "blocked" : "done";
}

/**
 * Whether a row is shown before the disclosure is opened.
 *
 * An unrecognised event type is in, deliberately: this version cannot judge
 * whether it matters, and a screen that silently dropped it would be wrong in
 * the direction that hides things.
 */
export function inStory(entry: AuditEntry): boolean {
  return (
    STORY.has(entry.eventType) ||
    BLOCKED.has(entry.eventType) ||
    !(entry.eventType in EVENT_WORDS)
  );
}

/**
 * An instant as the e-mails write it — "24 October 2026 at 21:51" — in the
 * reader's own zone.
 *
 * `zone` exists so the tests do not depend on the machine they run on; left
 * out, it is the browser's. An unparseable or absent value is null rather
 * than the string "Invalid Date": a timeline that cannot read a timestamp
 * should show a row without one, not a row that looks broken.
 */
export function at(iso: string | null, zone?: string): string | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const parts = new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: zone,
  }).formatToParts(date);
  const of = (type: string) => parts.find((part) => part.type === type)?.value ?? "";
  return `${of("day")} ${of("month")} ${of("year")} at ${of("hour")}:${of("minute")}`;
}

/** The time alone, for a row on the same day as the one above it. */
function timeOnly(formatted: string): string {
  return formatted.slice(formatted.indexOf(" at ") + 4);
}

/**
 * The row that has not happened yet, read from the workflow and not from the
 * trail.
 *
 * It is the current state rather than the last event on purpose: the last
 * event says who was activated, the workflow says who is being waited for
 * now, and when a slot has since released its turn those are different
 * answers. Null once the workflow is finished — then the last row of the
 * story is the last thing that happened, and nothing follows it.
 */
export function pendingRow(
  workflow: Workflow,
  username: string,
): TimelineRow | null {
  if (FINISHED.includes(workflow.status)) {
    return null;
  }
  const base = { key: "now", who: null, when: null, story: true, recorded: false };
  if (workflow.blockedReason) {
    // The server's own sentence, shown as given — the rule api.ts keeps for
    // its messages. Nothing here knows more about why than it does.
    return { ...base, words: workflow.blockedReason, mark: "blocked" };
  }
  // DRAFT means the document cannot be prepared yet because a slot has no
  // certificate. What is being waited for is a certificate, not a signature,
  // and the server names the slot it is waiting on — so this is said before
  // the signature wording below, which would otherwise claim somebody is
  // being asked to sign a document that has not been prepared.
  if (workflow.status === "DRAFT") {
    const who =
      workflow.currentSignerUserId === username
        ? "your certificate"
        : workflow.currentSignerUserId
          ? `a certificate from ${workflow.currentSignerUserId}`
          : "the signers' certificates";
    return { ...base, words: `Waiting for ${who}`, mark: "pending" };
  }
  if (workflow.waitingForClaim) {
    return {
      ...base,
      words: "Waiting for one of the signers to take the next turn",
      mark: "pending",
    };
  }
  if (workflow.currentSignerUserId) {
    return {
      ...base,
      words:
        workflow.currentSignerUserId === username
          ? "Waiting for you"
          : `Waiting for ${workflow.currentSignerUserId}`,
      mark: "pending",
    };
  }
  return { ...base, words: "Not sent out yet", mark: "pending" };
}

/**
 * The whole timeline, oldest first, with the pending row last.
 *
 * Dates repeat only when the day changes: every row carrying its full date
 * reads as a column of noise, and a row carrying only a time reads as "later
 * the same day", which is what it means. The server already answers in
 * sequence order; this sorts by it anyway rather than trusting the transport
 * to have preserved it.
 */
export function buildTimeline(
  workflow: Workflow,
  audit: AuditEntry[],
  username: string,
  zone?: string,
): TimelineRow[] {
  const sorted = [...audit].sort((a, b) => a.sequence - b.sequence);
  let previousDay: string | null = null;

  const rows: TimelineRow[] = sorted.map((entry) => {
    const full = at(entry.occurredAt, zone);
    const day = full ? full.slice(0, full.indexOf(" at ")) : null;
    const when = full && day === previousDay ? timeOnly(full) : full;
    if (day) {
      previousDay = day;
    }
    return {
      key: String(entry.sequence),
      words: words(entry),
      who: entry.actor,
      when,
      mark: markOf(entry),
      story: inStory(entry),
      recorded: true,
    };
  });

  const pending = pendingRow(workflow, username);
  return pending ? [...rows, pending] : rows;
}
