"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AuthShell } from "@/components/auth-shell";
import { Links, TextLink } from "@/components/blocks";
import { Busy, Field, NoteAlert, useNote } from "@/components/session";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { MIN_PASSWORD_LENGTH, api } from "@/lib/api";

/**
 * Set a new password from an emailed link.
 *
 * The token is read from the query string in the browser rather than through
 * `useSearchParams`, which in a static export would need a Suspense boundary
 * to say the same thing. A missing token is refused before anything is asked
 * of the visitor — there is no point taking a password that cannot be used.
 */
export default function ResetPassword() {
  const { note, say } = useNote();
  const [token, setToken] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    setToken(new URLSearchParams(window.location.search).get("token") ?? "");
  }, []);

  async function submit() {
    if (!token) return;
    setBusy(true);
    say("Setting your new password…");
    const result = await api.confirmPasswordReset(token, password);
    setBusy(false);
    if (result.ok) {
      setDone(true);
      return;
    }
    // One message covers unknown, expired and already-used, as the server
    // sends it: the difference is deliberately not reported.
    say(result.message ?? "That link is no longer usable.", "bad");
  }

  if (token === null) {
    return (
      <AuthShell title="Checking the link…">
        <Skeleton className="h-11 w-full" />
      </AuthShell>
    );
  }

  if (token === "") {
    return (
      <AuthShell
        title="That link is incomplete"
        lede="Open the link from the email again, or ask for a new one."
        below={
          <Links>
            <TextLink href="/forgot-password">Ask for a new link</TextLink>
          </Links>
        }
      >
        <Button asChild size="lg" className="w-full">
          <Link href="/forgot-password">Ask for a new link</Link>
        </Button>
      </AuthShell>
    );
  }

  if (done) {
    return (
      <AuthShell
        title="Password set"
        lede="Every session of this account has been signed out, here and in the SignFlow app. Sign in with the new password."
      >
        <Button asChild size="lg" className="w-full">
          <Link href="/sign-in">Sign in</Link>
        </Button>
      </AuthShell>
    );
  }

  return (
    <AuthShell
      title="Set a new password"
      lede="This link works once. Afterwards the new password works everywhere."
    >
      <form
        className="space-y-5"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <Field
          label="New password"
          name="newPassword"
          type="password"
          value={password}
          onChange={setPassword}
          autoComplete="new-password"
          autoFocus
          minLength={MIN_PASSWORD_LENGTH}
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          disabled={busy}
        />
        <Button type="submit" size="lg" className="w-full" disabled={busy}>
          {busy ? <Busy>Saving…</Busy> : "Set the password"}
        </Button>
      </form>
      <NoteAlert note={note} />
    </AuthShell>
  );
}
