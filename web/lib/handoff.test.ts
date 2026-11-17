import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CODE_SECONDS,
  FOCUS_GRACE_MS,
  POLL_LIMIT_MS,
  POLL_MS,
  TRUST_WORDS,
  appNotDetected,
  clock,
  outcomeFor,
  pollDelay,
  pollExhausted,
  refusalWords,
  secondsLeft,
  settled,
  signedAt,
  worthRetrying,
} from "./handoff.ts";

/**
 * The hand-off screen's clock and its refusals, tested without waiting two
 * minutes for a real one.
 *
 * The clock is the part worth testing: it is the only thing in this website
 * whose truth changes while the reader does nothing, and the screen's one
 * primary action depends on which side of zero it is.
 */

const ISSUED = 1_700_000_000_000;

test("the clock counts down from the lifetime the server gave", () => {
  assert.equal(secondsLeft(ISSUED, 120, ISSUED), 120);
  assert.equal(secondsLeft(ISSUED, 120, ISSUED + 1_000), 119);
  assert.equal(secondsLeft(ISSUED, 120, ISSUED + 119_000), 1);
  assert.equal(secondsLeft(ISSUED, 120, ISSUED + 120_000), 0);
});

test("a screen left open for an hour says zero, not a negative number", () => {
  assert.equal(secondsLeft(ISSUED, 120, ISSUED + 3_600_000), 0);
  assert.equal(clock(secondsLeft(ISSUED, 120, ISSUED + 3_600_000)), "0:00");
});

test("the server's lifetime is used, not this module's idea of it", () => {
  // CODE_SECONDS is what Phase B sets today; the server still gets to say.
  assert.equal(CODE_SECONDS, 120);
  assert.equal(secondsLeft(ISSUED, 30, ISSUED + 10_000), 20);
});

test("the countdown does not change width as it falls", () => {
  assert.equal(clock(120), "2:00");
  assert.equal(clock(118), "1:58");
  assert.equal(clock(60), "1:00");
  assert.equal(clock(59), "0:59");
  assert.equal(clock(7), "0:07");
  assert.equal(clock(0), "0:00");
  assert.equal(clock(-5), "0:00");
});

test("only a refusal a retry could fix offers one", () => {
  // Five a minute is the limit; waiting is all it takes.
  assert.equal(worthRetrying("TOO_MANY_REQUESTS"), true);
  // A network failure is worth another go too.
  assert.equal(worthRetrying("UNREACHABLE"), true);
  // These are states, not accidents: pressing again cannot change them.
  assert.equal(worthRetrying("CERTIFICATE_NOT_BOUND"), false);
  assert.equal(worthRetrying("NOT_YOUR_TURN"), false);
  assert.equal(worthRetrying("INVALID_WORKFLOW_STATE"), false);
  assert.equal(worthRetrying("EMAIL_NOT_VERIFIED"), false);
  assert.equal(worthRetrying(null), false);
});

test("a refusal shows the server's own sentence", () => {
  // The server knows which of the reasons it is, and its wording already
  // tells the reader what to do about an unregistered card.
  const said =
    "Your smart card is not registered for this signing task yet."
    + " Open the task in SignFlow while signed in and connect your card first.";
  assert.equal(refusalWords(said), said);
});

test("a refusal with no message still says something true", () => {
  assert.equal(
    refusalWords(null),
    "This document cannot be opened for signing right now.",
  );
});

test("silence for the whole grace window means the app was not detected", () => {
  assert.equal(FOCUS_GRACE_MS, 2500);
  // The page never heard the application take focus, and the window is over.
  assert.equal(appNotDetected(false, 2500), true);
  assert.equal(appNotDetected(false, 10_000), true);
  // Inside the window there is not yet evidence either way.
  assert.equal(appNotDetected(false, 2499), false);
  assert.equal(appNotDetected(false, 0), false);
});

test("a page that lost focus means the application opened", () => {
  // The point of the probe: the app (or the accepted dialog) took the
  // focus, however long it took.
  assert.equal(appNotDetected(true, 10_000), false);
  assert.equal(appNotDetected(true, 0), false);
});

test("a custom grace window is honoured, for slow machines and tests", () => {
  assert.equal(appNotDetected(false, 700, 700), true);
  assert.equal(appNotDetected(false, 699, 700), false);
});

test("the poll asks every two seconds and gives up after ten minutes", () => {
  assert.equal(POLL_MS, 2000);
  assert.equal(POLL_LIMIT_MS, 600000);
  assert.equal(pollExhausted(1000, 1000 + POLL_LIMIT_MS - 1), false);
  assert.equal(pollExhausted(1000, 1000 + POLL_LIMIT_MS), true);
});

test("a dropped connection backs off, and one success resets it", () => {
  assert.equal(pollDelay(0), 2000);
  assert.equal(pollDelay(1), 4000);
  assert.equal(pollDelay(2), 8000);
  assert.equal(pollDelay(3), 16000);
  assert.equal(pollDelay(4), 30000);
  // It stops climbing rather than growing without bound.
  assert.equal(pollDelay(40), 30000);
  // Negative or zero is the fresh state.
  assert.equal(pollDelay(-1), 2000);
});

test("every workflow status the server sends maps to an outcome", () => {
  assert.equal(outcomeFor("COMPLETED"), "signed");
  assert.equal(outcomeFor("CANCELLED"), "stopped");
  assert.equal(outcomeFor("FAILED"), "stopped");
  assert.equal(outcomeFor("SIGNATURE_SUBMITTED"), "working");
  assert.equal(outcomeFor("VALIDATING"), "working");
  assert.equal(outcomeFor("WAITING_FOR_SIGNER"), "waiting");
  assert.equal(outcomeFor("READY_FOR_SIGNING"), "waiting");
  assert.equal(outcomeFor("DRAFT"), "waiting");
});

test("a status this version has never heard of reads as waiting, not a blank screen", () => {
  assert.equal(outcomeFor("SOMETHING_NEW"), "waiting");
  assert.equal(outcomeFor(null), "waiting");
  assert.equal(outcomeFor(undefined), "waiting");
});

test("polling stops on a signature or a stop, and only then", () => {
  assert.equal(settled("signed"), true);
  assert.equal(settled("stopped"), true);
  assert.equal(settled("working"), false);
  assert.equal(settled("waiting"), false);
});

test("when it was signed comes out of the audit trail", () => {
  const audit = [
    { eventType: "WORKFLOW_CREATED", occurredAt: "2026-11-17T09:00:00Z" },
    { eventType: "SIGNATURE_SUBMITTED", occurredAt: "2026-11-17T09:02:00Z" },
    { eventType: "WORKFLOW_COMPLETED", occurredAt: "2026-11-17T09:03:00Z" },
  ];
  assert.equal(signedAt(audit), "2026-11-17T09:03:00Z");
});

test("no completion event, or no trail at all, is null rather than a guess", () => {
  assert.equal(signedAt([{ eventType: "WORKFLOW_CREATED", occurredAt: "x" }]), null);
  assert.equal(signedAt([]), null);
  assert.equal(signedAt(null), null);
});

test("the last completion wins, so a re-read does not depend on row count", () => {
  const audit = [
    { eventType: "WORKFLOW_COMPLETED", occurredAt: "2026-11-17T09:03:00Z" },
    { eventType: "WORKFLOW_COMPLETED", occurredAt: "2026-11-17T09:09:00Z" },
  ];
  assert.equal(signedAt(audit), "2026-11-17T09:09:00Z");
});

test("the success screen does not claim trust while F-01 is open", () => {
  // The server verifies the signature over the bytes and the bound
  // certificate; it does NOT check that certificate against a trust anchor,
  // and sends the website no verdict at all. So the wording must not say
  // "verified", "valid" or "trusted".
  assert.match(TRUST_WORDS, /intact/);
  assert.match(TRUST_WORDS, /not verified/);
  assert.doesNotMatch(TRUST_WORDS, /\btrusted\b/i);
  assert.doesNotMatch(TRUST_WORDS, /\bvalid\b/i);
});
