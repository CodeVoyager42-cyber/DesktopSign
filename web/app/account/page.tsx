"use client";

import Link from "next/link";
import { useState } from "react";
import { CheckCircle2, ChevronRight, CircleAlert, KeyRound, Mail } from "lucide-react";
import { PageHeader, SectionCard } from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import {
  Busy,
  NoteAlert,
  SessionPending,
  useNote,
  useSession,
} from "@/components/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

/** The account screen: the address and its state, and the password. */
export default function Account() {
  const { me, state } = useSession();
  const { note, say } = useNote();
  const [busy, setBusy] = useState(false);

  async function resend(email: string) {
    setBusy(true);
    say("Sending…");
    const result = await api.resendVerification(email);
    setBusy(false);
    say(
      result.status === 429
        ? "Too many attempts — please wait a few minutes and try again."
        : // 202 either way, so the wording cannot claim an address exists.
          "If that address still needs confirming, another link is on its way.",
      result.status === 429 ? "bad" : "info",
    );
  }

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  return (
    <AppShell me={me} narrow>
      <PageHeader title="Your account" description={me.username} />

      <div className="space-y-6">
        <SectionCard
          title="Email address"
          description={me.email ?? "No email address on file."}
          icon={Mail}
        >
          {me.emailVerified ? (
            <Badge
              variant="secondary"
              className="gap-1.5 rounded-full border-transparent bg-success/10 px-2.5 py-1 text-sm font-medium text-success-strong"
            >
              <CheckCircle2 aria-hidden="true" className="size-4" />
              Confirmed
            </Badge>
          ) : (
            <>
              <Badge
                variant="secondary"
                className="gap-1.5 rounded-full border-transparent bg-warning/10 px-2.5 py-1 text-sm font-medium text-warning-strong"
              >
                <CircleAlert aria-hidden="true" className="size-4" />
                Not confirmed yet
              </Badge>
              <p className="text-base text-muted-foreground">
                You can look around, but sending a document and signing one
                both wait until this address is confirmed.
              </p>
              <Button
                type="button"
                variant="outline"
                disabled={busy}
                onClick={() => resend(me.email ?? "")}
              >
                {busy ? <Busy>Sending…</Busy> : "Send the confirmation link again"}
              </Button>
            </>
          )}
        </SectionCard>

        <SectionCard
          title="Password"
          description="Changing it signs out every other session, here and in the SignFlow app, and emails you to say it happened."
          icon={KeyRound}
        >
          <Button asChild variant="outline">
            <Link href="/change-password">
              Change password
              <ChevronRight aria-hidden="true" />
            </Link>
          </Button>
        </SectionCard>

        <NoteAlert note={note} />
      </div>
    </AppShell>
  );
}
