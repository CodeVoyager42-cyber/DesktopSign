"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { CheckCircle2, CircleAlert, Loader2 } from "lucide-react";
import { AuthShell } from "@/components/auth-shell";
import { Links, TextLink } from "@/components/blocks";
import { Button } from "@/components/ui/button";

import { api } from "@/lib/api";

/**
 * Confirm an email address.
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

  if (state === "working") {
    return (
      <AuthShell title="Confirming your address">
        <p
          className="flex items-center gap-2 text-base text-muted-foreground"
          role="status"
          aria-live="polite"
        >
          <Loader2 aria-hidden="true" className="size-5 animate-spin text-primary" />
          One moment…
        </p>
      </AuthShell>
    );
  }

  if (state === "done") {
    return (
      <AuthShell
        title="Address confirmed"
        lede="That is everything. You can send documents and sign them now."
      >
        <p className="flex items-center gap-2 text-base font-medium text-success">
          <CheckCircle2 aria-hidden="true" className="size-5" />
          Your email address is confirmed.
        </p>
        <Button asChild size="lg" className="w-full">
          <Link href="/documents">Go to my documents</Link>
        </Button>
      </AuthShell>
    );
  }

  if (state === "refused") {
    return (
      <AuthShell
        title="That link is no longer usable"
        lede="Confirmation links work once and then expire."
        below={
          <Links>
            <TextLink href="/sign-in">Sign in</TextLink>
          </Links>
        }
      >
        <p className="flex items-start gap-2 text-base text-muted-foreground">
          <CircleAlert aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-warning" />
          Sign in and ask for a new link from your account screen.
        </p>
        <Button asChild size="lg" className="w-full">
          <Link href="/sign-in">Sign in</Link>
        </Button>
      </AuthShell>
    );
  }

  return (
    <AuthShell
      title="That link is incomplete"
      lede="Open the link from the email again."
      below={
        <Links>
          <TextLink href="/sign-in">Sign in</TextLink>
        </Links>
      }
    >
      <Button asChild size="lg" className="w-full">
        <Link href="/sign-in">Sign in</Link>
      </Button>
    </AuthShell>
  );
}
