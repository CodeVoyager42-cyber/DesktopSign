import assert from "node:assert/strict";
import { test } from "node:test";
import { fileSize, groupedChecksum, versionLabel } from "./download.ts";

/**
 * The download page's three formatters.
 *
 * Worth testing because each has a case that would be wrong in a way nobody
 * notices in review: a zero size, a version the server could not parse, and a
 * checksum that is not one.
 */

test("a size reads the way the browser's own download shelf reads it", () => {
  // Powers of ten, like every browser, so the two numbers agree.
  assert.equal(fileSize(84_200_000), "84.2 MB");
  assert.equal(fileSize(950_000), "950 kB");
  assert.equal(fileSize(1_500_000_000), "1.50 GB");
  assert.equal(fileSize(512), "512 bytes");
});

test("a size that is not a size shows nothing", () => {
  // The server sends 0 for an external link, whose size it cannot know.
  assert.equal(fileSize(0), "");
  assert.equal(fileSize(-1), "");
  assert.equal(fileSize(Number.NaN), "");
  assert.equal(fileSize(Number.POSITIVE_INFINITY), "");
});

test("a version becomes a label, and an unknown one becomes nothing", () => {
  assert.equal(versionLabel("0.1.0"), "Version 0.1.0");
  assert.equal(versionLabel("0.2.0-rc1"), "Version 0.2.0-rc1");
  // "Version unknown" on a download page reads as a fault.
  assert.equal(versionLabel("unknown"), "");
  assert.equal(versionLabel(null), "");
  assert.equal(versionLabel(undefined), "");
  assert.equal(versionLabel("   "), "");
});

test("a checksum is grouped so two can be compared by eye", () => {
  const hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  assert.equal(
    groupedChecksum(hash),
    "e3b0c442 98fc1c14 9afbf4c8 996fb924 27ae41e4 649b934c a495991b 7852b855",
  );
  // Eight groups of eight.
  assert.equal(groupedChecksum(hash).split(" ").length, 8);
});

test("an uppercase checksum is normalised, and a malformed one is left alone", () => {
  const upper = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855";
  assert.equal(groupedChecksum(upper).startsWith("e3b0c442 "), true);
  // Not 64 hex characters: shown as it came rather than silently hidden.
  assert.equal(groupedChecksum("not-a-hash"), "not-a-hash");
  assert.equal(groupedChecksum(null), "");
});
