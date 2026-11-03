/**
 * Reading the download's own description out loud.
 *
 * In its own module for the reasons `dashboard.ts` and `send.ts` are: a page
 * may only export the names Next.js knows about, and these two are worth
 * testing without a browser — a file size shown wrong is the kind of thing
 * nobody notices in review and everybody notices on the page.
 */

/**
 * A size as a person reads one: `84.2 MB`.
 *
 * **MB, not MiB**, and 1000 rather than 1024: this number sits next to a
 * download button, where it exists to be compared with what the browser's own
 * download shelf will say — and every browser counts in powers of ten there.
 * Being technically right and visibly different from the browser would make
 * the reader wonder which file they got.
 *
 * One decimal place up to a gigabyte, none below a megabyte: `950 kB` needs
 * no fraction, `84.2 MB` does.
 */
export function fileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return "";
  }
  if (bytes < 1000) {
    return `${Math.round(bytes)} bytes`;
  }
  if (bytes < 1000 * 1000) {
    return `${Math.round(bytes / 1000)} kB`;
  }
  if (bytes < 1000 * 1000 * 1000) {
    return `${(bytes / 1000 / 1000).toFixed(1)} MB`;
  }
  return `${(bytes / 1000 / 1000 / 1000).toFixed(2)} GB`;
}

/**
 * The version, as a label: `Version 0.1.0`.
 *
 * Null, unknown and blank all produce nothing rather than "Version unknown",
 * which reads as a fault on a download page. The server sends `"unknown"`
 * when it cannot parse a version out of the file name, and that is a fact
 * about the server, not something to show a reader.
 */
export function versionLabel(version: string | null | undefined): string {
  if (!version || version.trim() === "" || version === "unknown") {
    return "";
  }
  return `Version ${version.trim()}`;
}

/**
 * The checksum in groups of eight, so two of them can be compared by eye
 * without counting characters: a 64-character run of hex is unreadable, and
 * comparing one by eye is the only thing anybody does with it.
 *
 * Anything that is not a 64-character hex string is returned untouched — the
 * server validates the shape, and this is not the place to decide a
 * malformed one is worth hiding.
 */
export function groupedChecksum(sha256: string | null | undefined): string {
  if (!sha256) {
    return "";
  }
  const clean = sha256.trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(clean)) {
    return sha256;
  }
  return (clean.match(/.{1,8}/g) ?? []).join(" ");
}

/**
 * The day a release was published, as a reader reads a date: `2026-10-28`.
 *
 * UTC on purpose, so the page cannot disagree with itself about which day
 * it is, and nothing rather than a wrong date for a value that is not one —
 * the server sends null when GitHub did not say.
 */
export function releaseDate(publishedAt: string | null | undefined): string {
  if (!publishedAt) {
    return "";
  }
  const date = new Date(publishedAt);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return date.toISOString().slice(0, 10);
}

/**
 * Why the installer download did not start, in words a reader can act on.
 *
 * Mapped from the status rather than shown from the server's own `message`,
 * which is the opposite of what the account screens do — and deliberately.
 * Those endpoints answer deliberately vague things ("too many attempts")
 * that must be passed through verbatim; this one has exactly four outcomes
 * and each has a different thing for the reader to *do*, which the server's
 * wording does not say.
 *
 * 401 is absent on purpose: no session is not a message, it is a redirect to
 * sign in, and a caller that renders this string for a 401 has already
 * failed to send the reader away.
 */
export function installerFailure(status: number): string {
  if (status === 404) {
    return "No installer has been published yet. Your administrator publishes it for your organisation.";
  }
  if (status === 429) {
    return "Too many download attempts from this network. Wait a minute, then try again.";
  }
  if (status === 0) {
    return "The server could not be reached. Check your connection and try again.";
  }
  return "The download could not be started. Try again in a moment.";
}

/**
 * The exact PowerShell line for the file the reader received.
 *
 * The public download page shows a generic `.\{file}` because it cannot
 * know the name at authoring time; this component can, and a command with
 * the real name in it is one less thing to get wrong. Empty for nothing to
 * name, like every formatter here.
 */
export function verifyCommand(fileName: string | null | undefined): string {
  if (!fileName || fileName.trim() === "") {
    return "";
  }
  return `Get-FileHash -Algorithm SHA256 .\\${fileName.trim()}`;
}
