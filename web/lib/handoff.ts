/**
 * The hand-off to the desktop application: the clock, and what the screen is
 * doing (`docs/frontend-design.md` §6, "The Sign hand-off screen").
 *
 * In its own module for the reasons `timeline.ts` and `send.ts` are — a page
 * module may only export the names Next.js knows about, and a countdown is
 * worth testing without waiting two minutes for it.
 *
 * **What a launch code is.** 256 random bits, single use, valid for two
 * minutes, exchanged by the desktop for a session scoped to one signing task
 * (security Phase B). It is a credential, so this module never logs one and
 * the page never puts one anywhere a reader could copy it from by accident.
 * The deep link that carries it is navigated to, not displayed.
 *
 * **Why there is a clock at all.** The code expires while the reader is
 * looking at the screen. Saying so, and showing how long is left, is the
 * difference between "nothing happened, this is broken" and "the link timed
 * out, press Retry" — which is the only screen in this website where the
 * truth changes without anybody doing anything.
 */

/** Where the hand-off is. */
export type Phase =
  /** Asking the server for a code. */
  | "asking"
  /** A code is in hand and the application has been asked to open. */
  | "opening"
  /** The grace window passed and the page never lost focus: the application
   *  did not open, so the reader probably does not have it installed. */
  | "missing"
  /** The code's two minutes ran out. */
  | "expired"
  /** The server would not issue one, and said why. */
  | "refused";

/** The code's lifetime, as the server sets it (Phase B: `CODE_TTL`). */
export const CODE_SECONDS = 120;

/**
 * How long the page waits for evidence that the application opened, after
 * navigating to the deep link.
 *
 * A custom-scheme launch leaves traces the page can hear: the opening
 * application (or the browser's "open this?" dialog being accepted) takes
 * focus away from the tab, which arrives as a window `blur` or the document
 * becoming hidden. Silence past this window is the page's only honest signal
 * that nothing is installed — browsers expose no direct "is this protocol
 * registered?" question.
 */
export const FOCUS_GRACE_MS = 2500;

/**
 * Whether the deep-link attempt came back with nothing.
 *
 * True only when the grace window has fully passed *and* the page never lost
 * focus. Both inputs are measured facts, not guesses, so the decision stays
 * testable: a reader who merely dismissed the dialog quietly is reported as
 * "not detected" too — the screen's answer to that is always an install
 * link beside a Try again, never a dead end.
 */
export function appNotDetected(
  sawFocusLeave: boolean,
  elapsedMs: number,
  graceMs: number = FOCUS_GRACE_MS,
): boolean {
  return !sawFocusLeave && elapsedMs >= graceMs;
}

/**
 * Seconds left of a code issued at `issuedAt` for `lifetime` seconds.
 *
 * Never negative: a screen that has sat open for an hour says 0 and offers
 * Retry, rather than counting downwards into nonsense. The server's own
 * `expiresInSeconds` is passed in rather than assumed, so a server that
 * changes the lifetime changes this screen too.
 */
export function secondsLeft(issuedAt: number, lifetime: number, now: number): number {
  const left = Math.ceil((issuedAt + lifetime * 1000 - now) / 1000);
  return left > 0 ? left : 0;
}

/**
 * A countdown as a reader reads one: `1:58`, `0:07`.
 *
 * Minutes and seconds rather than "118 seconds", because the number is there
 * to be glanced at. Seconds are zero-padded so the text does not change width
 * as it counts down, which otherwise makes the line jump.
 */
export function clock(seconds: number): string {
  const safe = seconds > 0 ? seconds : 0;
  const minutes = Math.floor(safe / 60);
  const rest = safe % 60;
  return `${minutes}:${rest < 10 ? "0" : ""}${rest}`;
}

/**
 * Whether a refusal is worth offering Retry for.
 *
 * `TOO_MANY_REQUESTS` is: five codes a minute is the limit (Phase B), and a
 * reader who pressed the button too often only has to wait. Everything else
 * is a state that a retry cannot change — an unregistered card, somebody
 * else's turn, a workflow that is finished — so offering Retry would ask the
 * reader to press a button that cannot work.
 */
export function worthRetrying(code: string | null): boolean {
  return code === "TOO_MANY_REQUESTS" || code === "UNREACHABLE";
}

/**
 * What a refusal says.
 *
 * The server's own sentence, as given — the rule `api.ts` keeps for every
 * other call, and here it is also the better text: the server knows whether
 * the card is unregistered (and says to open the task in SignFlow while
 * signed in and connect it), whether it is somebody else's turn, or whether
 * the document is already finished. Only the fallback is ours, for a refusal
 * that arrives with no message at all.
 */
export function refusalWords(message: string | null): string {
  return message ?? "This document cannot be opened for signing right now.";
}

/* ------------------------------------------------------------------------ *
 * Waiting for the signature                                                 *
 * ------------------------------------------------------------------------ */

/**
 * How often to ask the server what became of the document, and for how long.
 *
 * Polling rather than a socket: the answer changes a handful of times in a
 * couple of minutes, the question is one indexed read, and a socket would be
 * a second transport to secure, authorise and keep alive for no gain at this
 * size. Ten minutes is the ceiling because that is far longer than inserting
 * a card and typing a PIN; past it the page stops asking and offers the
 * question back to the reader rather than hammering a server that is plainly
 * not going to answer differently.
 */
export const POLL_MS = 2000;
export const POLL_LIMIT_MS = 10 * 60 * 1000;

/**
 * Back-off after consecutive network failures: 2s, 4s, 8s, 16s, then 30s for
 * ever. A dropped connection is the common case (a laptop lid, a tunnel),
 * and retrying every two seconds through it is how a page becomes the reason
 * the network looks busy. The first success resets the count.
 */
export function pollDelay(consecutiveFailures: number): number {
  const ladder = [POLL_MS, 4000, 8000, 16000, 30000];
  if (consecutiveFailures <= 0) {
    return ladder[0];
  }
  return ladder[Math.min(consecutiveFailures, ladder.length - 1)];
}

/** True once the page has been asking for longer than the ceiling. */
export function pollExhausted(startedAt: number, now: number): boolean {
  return now - startedAt >= POLL_LIMIT_MS;
}

/**
 * What a workflow status means to somebody waiting on this screen.
 *
 *  - `signed`  — done, and the signed PDF can be downloaded.
 *  - `stopped` — terminal and not a signature: cancelled, or failed.
 *  - `working` — the desktop has it and the server is checking a signature.
 *  - `waiting` — nothing has happened yet.
 *
 * An unrecognised status reads as `waiting` rather than throwing: a server
 * that adds a state should not blank this screen, and "nothing yet" is the
 * honest reading of a word we do not know.
 */
export type Outcome = "waiting" | "working" | "signed" | "stopped";

export function outcomeFor(status: string | null | undefined): Outcome {
  switch (status) {
    case "COMPLETED":
      return "signed";
    case "CANCELLED":
    case "FAILED":
      return "stopped";
    case "SIGNATURE_SUBMITTED":
    case "VALIDATING":
      return "working";
    default:
      return "waiting";
  }
}

/** Terminal outcomes: the page stops polling on these and only these. */
export function settled(outcome: Outcome): boolean {
  return outcome === "signed" || outcome === "stopped";
}

/**
 * When the document was finished, out of its audit trail, or null.
 *
 * The trail is the only place the website can learn this: the workflow view
 * carries counts and whose turn it is, not a timestamp. Reads the LAST
 * completion event, because the trail is append-only and a re-read should not
 * depend on how many rows arrived.
 */
export function signedAt(
  audit: { eventType: string; occurredAt: string }[] | null,
): string | null {
  if (!audit) {
    return null;
  }
  const completions = audit.filter((row) => row.eventType === "WORKFLOW_COMPLETED");
  return completions.length === 0
    ? null
    : completions[completions.length - 1].occurredAt;
}

/**
 * What the success screen is allowed to claim about the signature.
 *
 * <b>Not "verified".</b> Finding F-01 is open: the server checks that the
 * signature is intact over the bytes and that the certificate matches the
 * one bound to the slot, but it does not validate that certificate against
 * any trust anchor — and it does not send a trust verdict to the website at
 * all. So this says what is true and stops. Changing it to claim trust needs
 * trust anchors first, and a field to carry the verdict.
 */
export const TRUST_WORDS = "Signature intact — signer certificate not verified";
