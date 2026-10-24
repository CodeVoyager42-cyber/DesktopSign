import type { NextConfig } from "next";

/**
 * A static export, served by the Spring Boot server from its own origin.
 *
 * That is the whole integration, and it is deliberate. The website
 * authenticates with a `__Host-` prefixed, HttpOnly session cookie and a CSRF
 * double-submit token (security Phase C); both of those depend on the page
 * and the API being the same origin. A Next.js server of its own would mean a
 * second runtime to deploy and patch, and same-origin only as long as a proxy
 * fronted both — so `next build` writes plain files here and the jar serves
 * them, exactly as it served the hand-written modules before.
 *
 * What this gives up: server rendering, route handlers and middleware. None
 * of them are a loss, because the Spring application *is* the backend — there
 * is no second place for server logic to live, and putting any there would
 * split the security model in two.
 */
const nextConfig: NextConfig = {
  output: "export",

  // The export is copied into src/main/resources/static, so every asset is
  // requested from the server's own origin. `script-src 'self'` permits them;
  // see docs/frontend-nextjs.md for the inline-bootstrap hashes.
  images: { unoptimized: true },

  // Routes export as `documents.html` rather than `documents/index.html`,
  // which keeps the Spring forward one line per page and leaves directory
  // index behaviour out of it.
  trailingSlash: false,

  // A build that type-checks and lints is the point of choosing TypeScript;
  // these stay false so a broken build fails here and not in a browser.
  eslint: { ignoreDuringBuilds: false },
  typescript: { ignoreBuildErrors: false },
};

export default nextConfig;
