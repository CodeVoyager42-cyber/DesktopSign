import type { InvitationStatus, SignerSpec } from "./api";

/**
 * The sending side: preparing what the create call needs, and reading back
 * where each invitation stands.
 *
 * In its own module for the reasons `dashboard.ts` and `timeline.ts` are — a
 * page module may only export the names Next.js knows about, and this is the
 * part worth testing without a browser.
 *
 * **What this deliberately does not validate.** Whether an address is
 * well formed, whether a username exists, whether two slots name the same
 * person: all of that is the server's judgement, and it answers with its own
 * message, which the screen shows as given (the rule `api.ts` keeps). What is
 * checked here is only what the form itself can know — that a file was
 * chosen, that it is a PDF, that there is a name, that somebody is named to
 * sign. A second copy of the server's rules in the browser is a second thing
 * to keep in step, and the screen would still have to believe the server.
 */

/** The two policies, in the words the screen uses for them. */
export const POLICIES = [
  {
    value: "SEQUENTIAL",
    label: "One after another",
    hint: "Each signer is asked once the one before them has signed.",
  },
  {
    value: "FLEXIBLE",
    label: "In any order",
    hint: "Anyone who has not signed can take the next turn.",
  },
] as const;

/**
 * The document's name, defaulted from the chosen file.
 *
 * The extension is kept: this name is what the finished PDF is downloaded as
 * (through `WorkflowOperations.attachment`), so dropping the `.pdf` would
 * hand everyone a file their system does not recognise. A path from a browser
 * file input is already just a name, but `\` and `/` are stripped anyway
 * rather than trusted not to appear.
 */
export function documentNameFor(fileName: string): string {
  const bare = fileName.split(/[\\/]/).pop() ?? "";
  return bare.trim();
}

/** Whether the server will read this slot as an address to invite. */
export function isEmail(who: string): boolean {
  // The server's own test is exactly this: an `@` means invite, anything
  // else means an account that must already exist. The shape of the address
  // is then its business, not ours.
  return who.includes("@");
}

/**
 * The signer slots, numbered.
 *
 * Blank rows are dropped rather than refused — a form with three rows and
 * two filled in means two signers, and making the reader delete the empty one
 * teaches them nothing. Order is 1-based and comes from position after the
 * blanks are gone, so slot numbers never have a hole in them.
 */
export function signersFrom(rows: string[]): SignerSpec[] {
  return rows
    .map((row) => row.trim())
    .filter((row) => row.length > 0)
    .map((userId, index) => ({ order: index + 1, userId, certificateSha256: null }));
}

/**
 * The first thing missing from the form, in the reader's words, or null when
 * there is nothing to say. One message at a time: a list of complaints about
 * a form the reader is still filling in is noise.
 */
export function problemWith(
  file: File | null,
  documentName: string,
  rows: string[],
): string | null {
  if (!file) {
    return "Choose the PDF you want signed.";
  }
  if (!file.name.toLowerCase().endsWith(".pdf")) {
    return "Only PDF documents can be sent for signing.";
  }
  if (!documentName.trim()) {
    return "Give the document a name.";
  }
  if (signersFrom(rows).length === 0) {
    return "Name at least one person to sign it.";
  }
  return null;
}

/* ---------------------- reading the invitations back ---------------------- */

/** Invitation states, in the reader's language. */
export const INVITATION_WORDS: Record<string, string> = {
  PENDING: "Invited",
  ACCEPTED: "Opened",
  EXPIRED: "The invitation expired",
  REVOKED: "Withdrawn",
  DECLINED: "Declined",
  COMPLETED: "Signed",
};

/**
 * What one slot's row says: where the invitation stands, and what became of
 * the e-mail.
 *
 * Signed wins over everything: once a slot is signed, whether its invitation
 * is still pending is bookkeeping, and reading "Invited" against a signature
 * would be wrong in the way that matters.
 */
export function slotStory(slot: InvitationStatus): string {
  if (slot.slotSigned) {
    return "Signed";
  }
  const state = INVITATION_WORDS[slot.status] ?? slot.status;
  if (slot.status === "PENDING" && slot.emailStatus === "FAILED") {
    // The invitation is live but nobody received it — the one combination
    // where the e-mail's fate matters more than the invitation's state.
    return "Invited, but the e-mail could not be delivered";
  }
  if (slot.status === "PENDING" && slot.emailStatus === "PENDING") {
    return "Invited, e-mail on its way";
  }
  return state;
}

/** Whether this row is worth drawing attention to. */
export function slotNeedsAttention(slot: InvitationStatus): boolean {
  if (slot.slotSigned) {
    return false;
  }
  return (
    slot.emailStatus === "FAILED" ||
    slot.status === "EXPIRED" ||
    slot.status === "DECLINED"
  );
}

/**
 * A slot can be invited again while it is unsigned and there is an invitation
 * to replace. Resending revokes the old token and issues a new one, so it is
 * also the way out of a failed delivery or an expiry.
 */
export function canResend(slot: InvitationStatus): boolean {
  return !slot.slotSigned && slot.invitationId !== null && slot.status !== "REVOKED";
}

/**
 * Withdrawing only makes sense while the invitation could still be used. A
 * signed slot cannot be un-signed, and a revoked or expired token is already
 * dead.
 */
export function canWithdraw(slot: InvitationStatus): boolean {
  return (
    !slot.slotSigned &&
    slot.invitationId !== null &&
    (slot.status === "PENDING" || slot.status === "ACCEPTED")
  );
}
