import type { SignerSpec } from "./api";

/**
 * The sending side: preparing what the create call needs.
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
 * A file size as a reader reads one: `1.4 MB`, `820 KB`.
 *
 * The preview screen shows it so a reader who picked the wrong file from a
 * folder of similarly named PDFs notices before sending. One decimal is
 * enough to tell 1.4 MB from 1.8 MB; below a megabyte, whole kilobytes.
 */
export function readableSize(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

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

/**
 * Whether the server will refuse this slot for looking like an address.
 *
 * The server's own test is exactly this: an `@` is refused, anything else is
 * read as an account that must already exist. The shape of the address is
 * then nobody's business, because it is never used.
 */
export function isEmail(who: string): boolean {
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



/* ------------------------------------------------------------------------ *
 * What the upload screen can check before sending anything                  *
 * ------------------------------------------------------------------------ */

/** A PDF begins with these five bytes (ISO 32000-1 7.5.2). */
export const PDF_MAGIC = "%PDF-";

/**
 * The ceiling to assume when the server has not said yet.
 *
 * The real number comes from GET /web/api/app-info (`maxUploadBytes`), which
 * reads the server's own multipart limit, so the two cannot disagree. This
 * constant exists only for the moment before that answer arrives, and it
 * matches the shipped default (100 MiB).
 */
export const MAX_UPLOAD_FALLBACK_BYTES = 104857600;

/**
 * True when these bytes start a PDF.
 *
 * Defence in depth, not security: the server checks the same thing and its
 * answer is the one that counts (finding S-03). The point here is to tell
 * somebody who picked the wrong file straight away, instead of after a
 * hundred megabytes have gone up the wire.
 */
export function looksLikePdf(firstBytes: Uint8Array): boolean {
  if (firstBytes.length < PDF_MAGIC.length) {
    return false;
  }
  for (let i = 0; i < PDF_MAGIC.length; i++) {
    if (firstBytes[i] !== PDF_MAGIC.charCodeAt(i)) {
      return false;
    }
  }
  return true;
}

/**
 * What is wrong with the chosen file before it is read, or null.
 *
 * Size and name only — the bytes are checked separately, because reading
 * them is asynchronous and this has to answer while the reader is still
 * looking at the form.
 */
export function uploadProblem(
  file: { name: string; size: number } | null,
  maxBytes: number,
): string | null {
  if (!file) {
    return "Choose the PDF you want to sign.";
  }
  if (!file.name.toLowerCase().endsWith(".pdf")) {
    return "Only PDF documents can be signed.";
  }
  if (file.size === 0) {
    return "That file is empty.";
  }
  if (file.size > maxBytes) {
    return `That file is ${readableSize(file.size)}. The largest this server accepts is ${readableSize(maxBytes)}.`;
  }
  return null;
}
