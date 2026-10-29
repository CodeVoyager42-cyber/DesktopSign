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
  /** The code's two minutes ran out. */
  | "expired"
  /** The server would not issue one, and said why. */
  | "refused";

/** The code's lifetime, as the server sets it (Phase B: `CODE_TTL`). */
export const CODE_SECONDS = 120;

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
 * else's turn, a workflow that is finished — so offering Retry would be
 * inviting the reader to press a button that cannot work.
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
