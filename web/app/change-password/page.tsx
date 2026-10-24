"use client";

import Link from "next/link";
import { useState } from "react";
import { AppHeader, SiteFooter } from "../../components/SiteChrome";
import {
  Field,
  Links,
  NoteRegion,
  SessionPending,
  Sheet,
  useNote,
  useSession,
} from "../../components/ui";
import { MIN_PASSWORD_LENGTH, api } from "../../lib/api";

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

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  async function submit() {
    setBusy(true);
    say("Changing your password…");
    const result = await api.changePassword(current, next);
    setBusy(false);
    if (result.ok) {
      setDone(true);
      return;
    }
    say(result.message ?? "That did not work.", "bad");
  }

  return (
    <>
      <AppHeader me={me} />
      <main id="main" className="panel">
        {done ? (
          <>
            <h1>Password changed</h1>
            <p className="lede">
              Every other session has been signed out, and you have been
              e-mailed to say it happened. This one stays signed in.
            </p>
            <Links>
              <Link href="/account">Back to your account</Link>
            </Links>
          </>
        ) : (
          <>
            <h1>Change password</h1>
            <p className="lede">
              At least {MIN_PASSWORD_LENGTH} characters. You stay signed in
              here; everywhere else is signed out.
            </p>
            <Sheet onSubmit={submit} submitLabel="Change password" busy={busy}>
              <Field
                label="Current password"
                name="currentPassword"
                type="password"
                value={current}
                onChange={setCurrent}
                autoComplete="current-password"
                autoFocus
              />
              <Field
                label="New password"
                name="newPassword"
                type="password"
                value={next}
                onChange={setNext}
                autoComplete="new-password"
                minLength={MIN_PASSWORD_LENGTH}
              />
            </Sheet>
            <NoteRegion note={note} />
            <Links>
              <Link href="/account">Cancel</Link>
            </Links>
          </>
        )}
      </main>
      <SiteFooter />
    </>
  );
}
