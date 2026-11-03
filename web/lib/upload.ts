/**
 * Preparing what the upload call needs.
 *
 * In its own module for the reasons `dashboard.ts` and `timeline.ts` are — a
 * page module may only export the names Next.js knows about, and this is the
 * part worth testing without a browser.
 *
 * **What this deliberately does not validate.** How large a PDF may be, and
 * whether the bytes really are a PDF: both are the server's judgement, and it
 * answers with its own message, which the screen shows as given (the rule
 * `api.ts` keeps). What is checked here is only what the form itself can know
 * — that a file was chosen, that it is named `.pdf`, and that there is a
 * document name.
 */

/**
 * The document's name, defaulted from the chosen file.
 *
 * The extension is kept: this name is what the finished PDF is downloaded as
 * (through `WorkflowOperations.attachment`), so dropping the `.pdf` would
 * hand back a file the reader's system does not recognise. A path from a
 * browser file input is already just a name, but `\` and `/` are stripped
 * anyway rather than trusted not to appear.
 */
export function documentNameFor(fileName: string): string {
  const bare = fileName.split(/[\\/]/).pop() ?? "";
  return bare.trim();
}

/**
 * The first thing missing from the form, in the reader's words, or null when
 * there is nothing to say. One message at a time: a list of complaints about
 * a form the reader is still filling in is noise.
 */
export function problemWith(
  file: File | null,
  documentName: string,
): string | null {
  if (!file) {
    return "Choose the PDF you want to sign.";
  }
  if (!file.name.toLowerCase().endsWith(".pdf")) {
    return "Only PDF documents can be signed.";
  }
  if (!documentName.trim()) {
    return "Give the document a name.";
  }
  return null;
}
