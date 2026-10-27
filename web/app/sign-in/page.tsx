"use client";

import { useState } from "react";
import { AuthShell } from "@/components/auth-shell";
import { Links, TextLink } from "@/components/blocks";
import { Busy, Field, NoteAlert, useNote } from "@/components/session";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

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
    <AuthShell
      title="Sign in"
      lede="With your email address or your username."
      below={
        <Links>
          <TextLink href="/forgot-password">Forgotten your password?</TextLink>
          <TextLink href="/sign-up">Create an account</TextLink>
        </Links>
      }
    >
      <form
        className="space-y-5"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <Field
          label="Email or username"
          name="username"
          type="text"
          value={username}
          onChange={setUsername}
          autoComplete="username"
          autoFocus
          disabled={busy}
        />
        <Field
          label="Password"
          name="password"
          type="password"
          value={password}
          onChange={setPassword}
          autoComplete="current-password"
          disabled={busy}
        />
        <Button type="submit" size="lg" className="w-full" disabled={busy}>
          {busy ? <Busy>Signing in…</Busy> : "Sign in"}
        </Button>
      </form>
      <NoteAlert note={note} />
    </AuthShell>
  );
}
