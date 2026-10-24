"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { SiteFooter, SiteHeader } from "../../components/SiteChrome";
import { Links } from "../../components/ui";
import { api } from "../../lib/api";

/**
 * Confirm an e-mail address.
 *
 * This page acts on arrival and then reports, because the link has already
 * been clicked — asking the visitor to click a second button to do what they
 * came for would be theatre. The token is single-use, so the effect guards
 * against running twice: in development React mounts an effect, unmounts and
 * mounts again, which would spend the token on the first pass and report the
 * second as invalid.
 */
export default function VerifyEmail() {
  const [state, setState] = useState<"working" | "done" | "refused" | "missing">(
    "working",
  );
  const asked = useRef(false);

  useEffect(() => {
    if (asked.current) return;
    asked.current = true;

    const token = new URLSearchParams(window.location.search).get("token");
    if (!token) {
      setState("missing");
      return;
    }
    api.verifyEmail(token).then((result) => {
      setState(result.ok ? "done" : "refused");
    });
  }, []);

  return (
    <>
      <SiteHeader />
      <main id="main" className="panel">
        {state === "working" ? (
          <>
            <h1>Confirming your address</h1>
            <p className="note" role="status" aria-live="polite">
              One moment…
            </p>
          </>
        ) : null}

        {state === "done" ? (
          <>
            <h1>Address confirmed</h1>
            <p className="lede">
              That is everything. You can create signing requests and sign
              documents now.
            </p>
            <Links>
              <Link href="/documents">Your documents</Link>
            </Links>
          </>
        ) : null}

        {state === "refused" ? (
          <>
            <h1>That link is no longer usable</h1>
            <p className="lede">
              Confirmation links work once and expire. Sign in and ask for a
              new one from your account screen.
            </p>
            <Links>
              <Link href="/sign-in">Sign in</Link>
            </Links>
          </>
        ) : null}

        {state === "missing" ? (
          <>
            <h1>That link is incomplete</h1>
            <p className="lede">Open the link from the e-mail again.</p>
            <Links>
              <Link href="/sign-in">Sign in</Link>
            </Links>
          </>
        ) : null}
      </main>
      <SiteFooter />
    </>
  );
}
