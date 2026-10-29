"use client";

import { useState } from "react";
import { MailCheck } from "lucide-react";
import { AuthShell } from "@/components/auth-shell";
import { Links, TextLink } from "@/components/blocks";
import { Busy, Field, NoteAlert, useNote } from "@/components/session";
import { Button } from "@/components/ui/button";
import { MIN_PASSWORD_LENGTH, api } from "@/lib/api";

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
  const [resent, setResent] = useState(false);

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

  async function resend() {
    setBusy(true);
    await api.resendVerification(email);
    setBusy(false);
    // 202 either way here too, so this says what was done and claims
    // nothing about whether an account exists.
    setResent(true);
  }

  if (sent) {
    return (
      <AuthShell
        title="Check your email"
        lede="If that address can be used, a link to confirm it is on its way. The link works once and then expires."
        below={
          <Links>
            <TextLink href="/sign-in">Sign in</TextLink>
            {/* The app can be installed while the confirmation email is in
                flight: the two are independent, and doing them in parallel
                is the shortest path to a first signature. */}
            <TextLink href="/download">Get the app for Windows</TextLink>
          </Links>
        }
      >
        <div className="flex items-start gap-3 rounded-xl bg-muted p-4">
          <MailCheck aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
          <div className="space-y-2 text-base">
            <p className="font-medium">
              Can&apos;t find it? Look in your Spam or All Mail folder.
            </p>
            <p className="text-muted-foreground">
              You can sign in before confirming and look around. Sending a
              document and signing one both wait until the address is
              confirmed.
            </p>
          </div>
        </div>
        <Button
          type="button"
          variant="outline"
          size="lg"
          className="w-full"
          disabled={busy || resent}
          onClick={resend}
        >
          {busy ? <Busy>Sending…</Busy> : resent ? "Link sent again" : "Send the link again"}
        </Button>
      </AuthShell>
    );
  }

  return (
    <AuthShell
      title="Create your account"
      lede="You need your smart card only when it is your turn to sign."
      below={
        <Links>
          <TextLink href="/sign-in">Already have an account? Sign in</TextLink>
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
        <Field
          label="Your name"
          name="displayName"
          type="text"
          value={displayName}
          onChange={setDisplayName}
          autoComplete="name"
          hint="Shown to the people you ask to sign."
          disabled={busy}
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
          disabled={busy}
        />
        <Button type="submit" size="lg" className="w-full" disabled={busy}>
          {busy ? <Busy>Creating your account…</Busy> : "Create account"}
        </Button>
      </form>
      <NoteAlert note={note} />
    </AuthShell>
  );
}
