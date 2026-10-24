"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, type Profile } from "../lib/api";

/**
 * The pieces every signed-in screen is built from: a message region that is
 * announced rather than only coloured, a labelled field, and a form whose one
 * primary action fills the width of the sheet.
 */

export type NoteKind = "info" | "bad" | "good";

export interface Note {
  message: string;
  kind: NoteKind;
}

/**
 * One `role="status"` region per screen, `aria-live="polite"`, so a refusal
 * or a confirmation is announced to a screen reader instead of being left to
 * a colour change nobody hears.
 */
export function NoteRegion({ note }: { note: Note | null }) {
  return (
    <p className={note ? `note ${note.kind}` : "note"} role="status" aria-live="polite">
      {note?.message ?? ""}
    </p>
  );
}

export function useNote() {
  const [note, setNote] = useState<Note | null>(null);
  const say = useCallback((message: string, kind: NoteKind = "info") => {
    setNote({ message, kind });
  }, []);
  const clear = useCallback(() => setNote(null), []);
  return { note, say, clear };
}

export function Field({
  label,
  name,
  type,
  value,
  onChange,
  autoComplete,
  autoFocus,
  minLength,
  hint,
}: {
  label: string;
  name: string;
  type: "text" | "email" | "password";
  value: string;
  onChange: (value: string) => void;
  autoComplete?: string;
  autoFocus?: boolean;
  minLength?: number;
  hint?: string;
}) {
  return (
    <>
      <p className="field">
        <label htmlFor={name}>{label}</label>
        <input
          id={name}
          name={name}
          type={type}
          value={value}
          required
          autoComplete={autoComplete}
          autoFocus={autoFocus}
          minLength={minLength}
          onChange={(event) => onChange(event.target.value)}
        />
      </p>
      {hint ? <p className="hint">{hint}</p> : null}
    </>
  );
}

/** A form in a sheet, with one full-width primary action. */
export function Sheet({
  onSubmit,
  submitLabel,
  busy,
  children,
}: {
  onSubmit: () => void;
  submitLabel: string;
  busy?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="sheet">
      <form
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit();
        }}
      >
        {children}
        <button type="submit" className="button block" disabled={busy}>
          {submitLabel}
        </button>
      </form>
    </div>
  );
}

export function Badge({ children, pending }: { children: React.ReactNode; pending?: boolean }) {
  return <span className={pending ? "badge pending" : "badge"}>{children}</span>;
}

export function Links({ children }: { children: React.ReactNode }) {
  return <p className="links">{children}</p>;
}

export function Separator() {
  return (
    <span className="sep" aria-hidden="true">
      ·
    </span>
  );
}

/**
 * What a signed-in page is wrapped in.
 *
 * The server is the authority on whether there is a session — the cookie is
 * HttpOnly and this code cannot read it — so every such page asks
 * `/web/api/auth/me` and renders nothing of itself until the answer is in.
 * Without a session it goes to the sign-in screen: a page is not an
 * authorisation, and the routes it would call would refuse it anyway.
 */
export function useSession() {
  const [me, setMe] = useState<Profile | null>(null);
  const [state, setState] = useState<"asking" | "in" | "out">("asking");

  useEffect(() => {
    let live = true;
    api.me().then((result) => {
      if (!live) return;
      if (result.ok && result.body) {
        setMe(result.body);
        setState("in");
      } else {
        setState("out");
        window.location.replace("/sign-in");
      }
    });
    return () => {
      live = false;
    };
  }, []);

  return { me, state };
}

/** The "still asking" and "being sent away" placeholder, so neither flashes. */
export function SessionPending() {
  return (
    <main id="main" className="panel">
      <p className="muted">Checking your session…</p>
    </main>
  );
}

export function BackToDocuments() {
  return <Link href="/documents">Documents</Link>;
}
