"use client";

import Link from "next/link";
import { useState } from "react";
import { SiteFooter, SiteHeader } from "../../components/SiteChrome";
import { Field, Links, NoteRegion, Separator, Sheet, useNote } from "../../components/ui";
import { api } from "../../lib/api";

/**
 * Sign in. One message for every kind of refusal — a wrong password, an
 * unknown account and a locked one are deliberately indistinguishable, and
 * the server's own wording is shown rather than a friendlier guess, because
 * being more helpful here would undo the server's work.
 */
export default function SignIn() {
  const { note, say } = useNote();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit() {
    setBusy(true);
    say("Signing in…");
    const result = await api.signIn(username, password);
    if (result.ok) {
      // A full navigation, not a client-side route change: the session
      // cookie has just been replaced and every later request should start
      // from the new one.
      window.location.assign("/documents");
      return;
    }
    setBusy(false);
    say(result.message ?? "That did not work.", "bad");
  }

  return (
    <>
      <SiteHeader />
      <main id="main" className="panel">
        <h1>Sign in</h1>
        <p className="lede">With your e-mail address or your username.</p>
        <Sheet onSubmit={submit} submitLabel="Sign in" busy={busy}>
          <Field
            label="E-mail or username"
            name="username"
            type="text"
            value={username}
            onChange={setUsername}
            autoComplete="username"
            autoFocus
          />
          <Field
            label="Password"
            name="password"
            type="password"
            value={password}
            onChange={setPassword}
            autoComplete="current-password"
          />
        </Sheet>
        <NoteRegion note={note} />
        <Links>
          <Link href="/forgot-password">Forgotten your password?</Link>
          <Separator />
          <Link href="/sign-up">Create an account</Link>
        </Links>
      </main>
      <SiteFooter />
    </>
  );
}
