import type { Metadata } from "next";
// Inter, self-hosted, from the package rather than from `next/font/google`.
//
// Both end up serving the font from this origin, which is what `font-src
// 'self'` requires — but `next/font/google` fetches it from Google at BUILD
// time, so the build then needs the internet and fails without it (it did
// here). `@fontsource-variable/inter` ships the .woff2 files in the package,
// under the same OFL-1.1 licence, so the build is offline and reproducible.
//
// This also closes the gap the hand-written stylesheet left behind: it
// pointed @font-face at two files nobody had added, and quietly fell back to
// the platform font.
import "@fontsource-variable/inter";
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
        <meta name="theme-color" content="#0e7c66" />
      </head>
      <body>
        <a className="skip" href="#main">
          Skip to main content
        </a>
        {children}
      </body>
    </html>
  );
}
