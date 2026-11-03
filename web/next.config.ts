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

/**
 * `next dev` and `next build` are deliberately NOT the same configuration,
 * and this is the only place they differ.
 *
 * `output: "export"` and `rewrites()` cannot both be set — an export has no
 * server to rewrite anything — so the export is applied for a build and the
 * proxy for development. What ships is therefore exactly what shipped
 * before: `npm run sync` runs `next build`, which is production, which takes
 * the first branch and writes the same static files.
 *
 * <b>Why a dev proxy at all.</b> Without it, `npm run dev` serves pages from
 * :3000 while the API answers on :8080, and every signed-in screen fails in
 * the same two ways: the session cookie is `__Host-` prefixed (so the browser
 * will not send it to a different origin) and the CSRF token is a
 * double-submit cookie (so it has to travel with the request). Forwarding
 * `/web/api` and `/sign` from :3000 to :8080 puts the page and the API back on
 * one origin as far as the browser is concerned, which is the only thing that
 * makes a dev server usable here.
 *
 * <b>What it does not do.</b> The proxy is a development convenience and
 * proves nothing about production: the real Content-Security-Policy, the
 * `Referrer-Policy`, the hash-permitted inline scripts and the forwards in
 * `FrontendRoutingConfiguration` are all applied by the Spring server on
 * :8080 and none of them exist on :3000. A change is only verified once it
 * has been through `npm run sync` and looked at on :8080 — see
 * `docs/frontend-nextjs.md` §4.
 */
const development = process.env.NODE_ENV === "development";

/** Where the API lives in development. */
const api = process.env.SIGNFLOW_API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  ...(development
    ? {
        async rewrites() {
          return [
            // Everything the website calls: the session chain.
            { source: "/web/api/:path*", destination: `${api}/web/api/:path*` },
            // The invitation pages the server renders itself, so a link out
            // of a dev mailbox opens the real thing rather than a 404.
            { source: "/sign/:path*", destination: `${api}/sign/:path*` },
          ];
        },
      }
    : { output: "export" as const }),

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
