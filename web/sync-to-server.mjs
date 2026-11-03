#!/usr/bin/env node
/**
 * Copies the static export into the Spring Boot application's own resources,
 * which is where it is served from.
 *
 * Run through `npm run sync`, which builds first. The result is **committed**,
 * deliberately: `mvn test` boots the server and asserts that it serves the
 * website, so the files have to be in the jar whether or not the machine
 * building it has Node installed. The alternative — making the Maven build
 * run npm — would put a Node toolchain in the way of every server build and
 * every CI job, for a frontend that changes far less often than the server.
 *
 * Generated files in version control are a real cost, so two things keep it
 * honest: this script is the only way they are produced, and it wipes what it
 * replaces, so nothing can linger from an older build and be served for ever.
 */

import { cp, mkdir, readdir, rm, stat } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const from = resolve(here, "out");
const to = resolve(here, "..", "src", "main", "resources", "static");

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

if (!(await exists(from))) {
  console.error(`No export at ${from}. Run \`npm run build\` first.`);
  process.exit(1);
}

// Wipe the destination rather than copying over it: a page that is no longer
// exported must stop being served, and a stale chunk nothing references is
// still something the server would hand out.
if (await exists(to)) {
  await rm(to, { recursive: true });
}
await mkdir(to, { recursive: true });
await cp(from, to, { recursive: true });

const files = [];
async function walk(dir, prefix = "") {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      await walk(path, `${prefix}${entry.name}/`);
    } else {
      files.push(`${prefix}${entry.name}`);
    }
  }
}
await walk(to);

const pages = files.filter((f) => f.endsWith(".html"));
console.log(`Copied ${files.length} files to src/main/resources/static`);
console.log(`  ${pages.length} pages: ${pages.sort().join(", ")}`);
console.log(
  "  Remember: FrontendRoutingConfiguration.PAGES lists which paths are"
  + " forwarded, and SecurityConfig reads that list.",
);
