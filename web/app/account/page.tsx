"use client";

import Link from "next/link";
import { useState } from "react";
import { AppHeader, SiteFooter } from "../../components/SiteChrome";
import {
  Badge,
  Links,
  NoteRegion,
  Separator,
  SessionPending,
  useNote,
  useSession,
} from "../../components/ui";
import { api } from "../../lib/api";

/** The account screen: the address and its state, the password, and signing out. */
export default function Account() {
  const { me, state } = useSession();
  const { note, say } = useNote();
  const [busy, setBusy] = useState(false);

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  async function resend() {
    setBusy(true);
    say("Sending…");
    await api.resendVerification(me!.email ?? "");
    setBusy(false);
    // 202 either way, so the wording cannot claim an address exists.
    say(
      "If that address still needs confirming, another link is on its way.",
      "info",
    );
  }

  async function signOut() {
    await api.signOut();
    window.location.assign("/sign-in");
  }

  return (
    <>
      <AppHeader me={me} />
      <main id="main" className="panel">
        <h1>{me.username}</h1>
        <p className="lede">{me.email ?? "No e-mail address on file."}</p>

        <article className="card">
          <h3>E-mail address</h3>
          <p>{me.email ?? ""}</p>
          {me.emailVerified ? (
            <Badge>Confirmed</Badge>
          ) : (
            <>
              <Badge pending>Not confirmed yet</Badge>
              <p>
                You can sign in and look around, but not create signing
                requests or sign documents until it is confirmed.
              </p>
              <p className="more">
                <button
                  type="button"
                  className="text-button"
                  onClick={resend}
                  disabled={busy}
                >
                  Send the confirmation link again
                </button>
              </p>
            </>
          )}
        </article>

        <article className="card">
          <h3>Password</h3>
          <p>
            Changing it signs out every other session, on the website and in
            the desktop application, and e-mails you to say it happened.
          </p>
          <p className="more">
            <Link href="/change-password">
              Change password <span aria-hidden="true">›</span>
            </Link>
          </p>
        </article>

        <NoteRegion note={note} />
        <Links>
          <Link href="/documents">Documents</Link>
          <Separator />
          <button type="button" className="text-button" onClick={signOut}>
            Sign out
          </button>
        </Links>
      </main>
      <SiteFooter />
    </>
  );
}
