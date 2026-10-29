"use client";

import { useState } from "react";
import { MailCheck } from "lucide-react";
import { AuthShell } from "@/components/auth-shell";
import { Links, TextLink } from "@/components/blocks";
import { Busy, Field, NoteAlert, useNote } from "@/components/session";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

/**
 * Ask for a password-reset link. 202 either way, for the same reason sign-up
 * is — so the confirmation says "if there is an account…" and means it.
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
      <AuthShell
        title="Check your email"
        lede="If there is an account for that address, a link to set a new password is on its way. It works once and expires in an hour."
        below={
          <Links>
            <TextLink href="/sign-in">Back to signing in</TextLink>
          </Links>
        }
      >
        <div className="flex items-start gap-3 rounded-xl bg-muted p-4 text-base">
          <MailCheck aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
          <p className="font-medium">
            Can&apos;t find it? Look in your Spam or All Mail folder.
          </p>
        </div>
      </AuthShell>
    );
  }

  return (
    <AuthShell
      title="Forgotten password"
      lede="Give the address on the account and we will send a link to set a new one."
      below={
        <Links>
          <TextLink href="/sign-in">Back to signing in</TextLink>
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
          label="Email address"
          name="email"
          type="email"
          value={email}
          onChange={setEmail}
          autoComplete="email"
          autoFocus
          disabled={busy}
        />
        <Button type="submit" size="lg" className="w-full" disabled={busy}>
          {busy ? <Busy>Sending…</Busy> : "Send the link"}
        </Button>
      </form>
      <NoteAlert note={note} />
    </AuthShell>
  );
}
