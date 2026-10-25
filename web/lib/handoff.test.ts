import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CODE_SECONDS,
  clock,
  refusalWords,
  secondsLeft,
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
