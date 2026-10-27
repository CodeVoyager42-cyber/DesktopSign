"use client";

import Link from "next/link";
import { useState } from "react";
import { CheckCircle2, KeyRound } from "lucide-react";
import { Links, PageHeader, SectionCard, TextLink } from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import {
  Busy,
  Field,
  NoteAlert,
  SessionPending,
  useNote,
  useSession,
} from "@/components/session";
import { Button } from "@/components/ui/button";
import { MIN_PASSWORD_LENGTH, api } from "@/lib/api";

/**
 * Change the password of the signed-in account. A screen of its own rather
 * than a panel on the account page, because one primary action per screen is
 * the rule here — and because it is then a link somebody can be sent.
 */
export default function ChangePassword() {
  const { me, state } = useSession();
  const { note, say } = useNote();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  async function submit() {
    setBusy(true);
    say("Changing your password…");
    const result = await api.changePassword(current, next);
    setBusy(false);
    if (result.ok) {
      setDone(true);
      return;
    }
    say(
      result.status === 429
        ? "Too many attempts — please wait a few minutes and try again."
        : (result.message ?? "That did not work."),
      "bad",
    );
  }

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  if (done) {
    return (
      <AppShell me={me} narrow>
        <PageHeader title="Password changed" />
        <SectionCard>
          <p className="flex items-start gap-2 text-base">
            <CheckCircle2
              aria-hidden="true"
              className="mt-0.5 size-5 shrink-0 text-success"
            />
            Every other session has been signed out, here and in the SignFlow
            app, and you have been emailed to say it happened. This one stays
            signed in.
          </p>
          <Button asChild size="lg" className="w-full">
            <Link href="/account">Back to my account</Link>
          </Button>
        </SectionCard>
      </AppShell>
    );
  }

  return (
    <AppShell me={me} narrow>
      <PageHeader
        title="Change password"
        description="You stay signed in here. Everywhere else is signed out."
      />
      <SectionCard title="New password" icon={KeyRound}>
        <form
          className="space-y-5"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <Field
            label="Current password"
            name="currentPassword"
            type="password"
            value={current}
            onChange={setCurrent}
            autoComplete="current-password"
            autoFocus
            disabled={busy}
          />
          <Field
            label="New password"
            name="newPassword"
            type="password"
            value={next}
            onChange={setNext}
            autoComplete="new-password"
            minLength={MIN_PASSWORD_LENGTH}
            hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
            disabled={busy}
          />
          <Button type="submit" size="lg" className="w-full" disabled={busy}>
            {busy ? <Busy>Changing…</Busy> : "Change password"}
          </Button>
        </form>
        <NoteAlert note={note} />
        <Links>
          <TextLink href="/account">Cancel</TextLink>
        </Links>
      </SectionCard>
    </AppShell>
  );
}
