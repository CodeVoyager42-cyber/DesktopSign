"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { SiteFooter, SiteHeader } from "../../components/SiteChrome";
import { Field, Links, NoteRegion, Sheet, useNote } from "../../components/ui";
import { MIN_PASSWORD_LENGTH, api } from "../../lib/api";

/**
 * Set a new password from an e-mailed link.
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
      <>
        <SiteHeader />
        <main id="main" className="panel">
          <p className="muted">Checking the link…</p>
        </main>
        <SiteFooter />
      </>
    );
  }

  if (token === "") {
    return (
      <>
        <SiteHeader />
        <main id="main" className="panel">
          <h1>That link is incomplete</h1>
          <p className="lede">
            Open the link from the e-mail again, or ask for a new one.
          </p>
          <Links>
            <Link href="/forgot-password">Ask for a new link</Link>
          </Links>
        </main>
        <SiteFooter />
      </>
    );
  }

  if (done) {
    return (
      <>
        <SiteHeader />
        <main id="main" className="panel">
          <h1>Password set</h1>
          <p className="lede">
            Every session of this account has been signed out, on the website
            and in the desktop application. Sign in with the new password.
          </p>
          <Links>
            <Link href="/sign-in">Sign in</Link>
          </Links>
        </main>
        <SiteFooter />
      </>
    );
  }

  return (
    <>
      <SiteHeader />
      <main id="main" className="panel">
        <h1>Set a new password</h1>
        <p className="lede">
          This link works once. Signing in again afterwards uses the new
          password everywhere.
        </p>
        <Sheet onSubmit={submit} submitLabel="Set the password" busy={busy}>
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
          />
        </Sheet>
        <NoteRegion note={note} />
      </main>
      <SiteFooter />
    </>
  );
}
