"use client";

import Link from "next/link";
import { useState } from "react";
import { SiteFooter, SiteHeader } from "../../components/SiteChrome";
import { Field, Links, NoteRegion, Sheet, useNote } from "../../components/ui";
import { MIN_PASSWORD_LENGTH, api } from "../../lib/api";

/**
 * Create an account.
 *
 * The server answers 202 whether or not anything happened — a taken address
 * is not reported, because that would turn this form into a way to test who
 * has an account — so the confirmation below is worded to be true either way.
 * The one thing it may report is whether the password met the rules, since
 * that describes the request and not who exists.
 */
export default function SignUp() {
  const { note, say } = useNote();
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);

  async function submit() {
    setBusy(true);
    say("Creating your account…");
    const result = await api.signUp(email, password, displayName);
    setBusy(false);
    if (result.ok) {
      setSent(true);
      return;
    }
    say(result.message ?? "That did not work.", "bad");
  }

  if (sent) {
    return (
      <>
        <SiteHeader />
        <main id="main" className="panel">
          <h1>Check your e-mail</h1>
          <p className="lede">
            If that address can be used, a confirmation link is on its way to
            it. The link works once and expires.
          </p>
          <div className="notice">
            <p>
              You can sign in before confirming, and look around — but
              creating signing requests and signing documents both wait until
              the address is confirmed.
            </p>
          </div>
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
        <h1>Create an account</h1>
        <p className="lede">
          You need a smart card only when it is your turn to sign.
        </p>
        <Sheet onSubmit={submit} submitLabel="Create an account" busy={busy}>
          <Field
            label="E-mail address"
            name="email"
            type="email"
            value={email}
            onChange={setEmail}
            autoComplete="email"
            autoFocus
          />
          <Field
            label="Your name"
            name="displayName"
            type="text"
            value={displayName}
            onChange={setDisplayName}
            autoComplete="name"
            hint="Shown to the people you ask to sign."
          />
          <Field
            label="Password"
            name="password"
            type="password"
            value={password}
            onChange={setPassword}
            autoComplete="new-password"
            minLength={MIN_PASSWORD_LENGTH}
            hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          />
        </Sheet>
        <NoteRegion note={note} />
        <Links>
          <Link href="/sign-in">Already have an account? Sign in</Link>
        </Links>
      </main>
      <SiteFooter />
    </>
  );
}
