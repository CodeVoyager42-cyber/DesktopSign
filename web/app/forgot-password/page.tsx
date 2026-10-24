"use client";

import Link from "next/link";
import { useState } from "react";
import { SiteFooter, SiteHeader } from "../../components/SiteChrome";
import { Field, Links, NoteRegion, Sheet, useNote } from "../../components/ui";
import { api } from "../../lib/api";

/**
 * Ask for a password-reset link. 202 either way, for the same reason sign-up
 * is — so the confirmation says "if that address…" and means it.
 */
export default function ForgotPassword() {
  const { note, say } = useNote();
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);

  async function submit() {
    setBusy(true);
    say("Sending…");
    await api.requestPasswordReset(email);
    setBusy(false);
    setSent(true);
  }

  if (sent) {
    return (
      <>
        <SiteHeader />
        <main id="main" className="panel">
          <h1>Check your e-mail</h1>
          <p className="lede">
            If there is an account for that address, a link to set a new
            password is on its way to it. The link works once and expires in
            an hour.
          </p>
          <Links>
            <Link href="/sign-in">Back to signing in</Link>
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
        <h1>Forgotten password</h1>
        <p className="lede">
          Give the address on the account and we will send a link to set a new
          password.
        </p>
        <Sheet onSubmit={submit} submitLabel="Send the link" busy={busy}>
          <Field
            label="E-mail address"
            name="email"
            type="email"
            value={email}
            onChange={setEmail}
            autoComplete="email"
            autoFocus
          />
        </Sheet>
        <NoteRegion note={note} />
        <Links>
          <Link href="/sign-in">Back to signing in</Link>
        </Links>
      </main>
      <SiteFooter />
    </>
  );
}
