import type { Metadata } from "next";
// Inter, self-hosted, from the package rather than from `next/font/google`.
//
// Both end up serving the font from this origin, which is what `font-src
// 'self'` requires — but `next/font/google` fetches it from Google at BUILD
// time, so the build then needs the internet and fails without it (it did
// here). `@fontsource-variable/inter` ships the .woff2 files in the package,
// under the same OFL-1.1 licence, so the build is offline and reproducible.
//
// `shadcn init` added a `next/font/google` Geist import here and it was
// removed again for exactly that reason: the generator cannot know that this
// deployment forbids a build-time fetch. Inter is wired to Tailwind's
// `--font-sans` in globals.css instead, so `font-sans` means Inter.
//
// `opsz.css`, not the package root. Inter carries an optical-size axis
// (opsz 14–32) and the root stylesheet exposes only the weight axis, so the
// axis was there and unreachable. These files carry both, under the same
// family name, the same OFL-1.1 licence and the same seven subsets, and they
// are shipped in the package exactly as before — the build stays offline.
//
// Nothing else is needed to use it: `font-optical-sizing: auto` is the CSS
// initial value, so the browser matches opsz to each element's font size on
// its own. Large text gets Inter's tighter display drawing and 14–16 px text
// its more open one, which is the whole point of the axis.
//
// It is not free: the font a Latin reader downloads goes from 48,444 to
// 73,080 bytes, +24.6 KB. Measured, and accepted deliberately.
import "@fontsource-variable/inter/opsz.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "SignFlow",
  description:
    "Send documents for signature, and sign them with the certificate on"
    + " your own smart card. The private key never leaves the card.",
  icons: { icon: "/favicon.ico" },
  // The e-mailed pages carry one-time tokens in the query string, and a
  // token must not leave in a Referer.
  referrer: "no-referrer",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <head>
        {/* The one literal colour outside globals.css, and it has to be: a
            browser paints its own chrome from this value before any
            stylesheet is parsed, so it cannot reference a CSS variable. It is
            `--primary`; change both together. */}
        <meta name="theme-color" content="#1d4ed8" />
      </head>
      <body className="min-h-dvh bg-background font-sans text-base text-foreground antialiased">
        {/* Visible as soon as it is focused: the first thing a keyboard
            reader meets on every page. */}
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
        >
          Skip to main content
        </a>
        {children}
      </body>
    </html>
  );
}
