"use client";

import { useCallback, useEffect, useState } from "react";
import { CheckCircle2, CircleAlert, Info, Loader2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { api, type Profile } from "@/lib/api";
import { cn } from "@/lib/utils";

/**
 * The session question, the message region, and the labelled field.
 *
 * Behaviour is carried over unchanged from the versions these replace — the
 * session is still the server's answer, messages are still announced rather
 * than only coloured — and only the rendering is shadcn's.
 */

export type NoteKind = "info" | "bad" | "good";

export interface Note {
  message: string;
  kind: NoteKind;
}

export function useNote() {
  const [note, setNote] = useState<Note | null>(null);
  const say = useCallback((message: string, kind: NoteKind = "info") => {
    setNote({ message, kind });
  }, []);
  const clear = useCallback(() => setNote(null), []);
  return { note, say, clear };
}

/**
 * One live region per screen, so a refusal or a confirmation is announced to
 * a screen reader instead of being left to a colour change nobody hears.
 * Three variants, each with its own icon: status is never colour alone.
 */
export function NoteAlert({ note }: { note: Note | null }) {
  if (!note) {
    // The region still exists when empty, so a message appearing in it is an
    // update to a live region rather than a new element nobody is told about.
    return <div role="status" aria-live="polite" className="sr-only" />;
  }

  const Icon =
    note.kind === "bad" ? CircleAlert : note.kind === "good" ? CheckCircle2 : Info;

  return (
    <div role="status" aria-live="polite">
      <Alert
        variant={note.kind === "bad" ? "destructive" : "default"}
        className={cn(
          "shadow-sm",
          note.kind === "good" && "border-success/30 text-success-strong [&>svg]:text-success",
        )}
      >
        <Icon aria-hidden="true" />
        <AlertDescription className="text-base">{note.message}</AlertDescription>
      </Alert>
    </div>
  );
}

/**
 * A field with its label above it, always.
 *
 * Never a placeholder standing in for a label: a placeholder disappears the
 * moment somebody types, which leaves them filling in a box whose purpose
 * they now have to remember. `hint` and `error` are both wired through
 * `aria-describedby`, and an error also sets `aria-invalid`, so the control
 * announces itself as wrong rather than only looking it.
 */
export function Field({
  label,
  name,
  type = "text",
  value,
  onChange,
  autoComplete,
  autoFocus,
  minLength,
  hint,
  error,
  placeholder,
  required = true,
  disabled,
  multiline,
}: {
  label: string;
  name: string;
  type?: "text" | "email" | "password" | "file";
  value?: string;
  onChange: (value: string) => void;
  autoComplete?: string;
  autoFocus?: boolean;
  minLength?: number;
  hint?: string;
  error?: string | null;
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  multiline?: boolean;
}) {
  const hintId = hint ? `${name}-hint` : undefined;
  const errorId = error ? `${name}-error` : undefined;
  const describedBy = [errorId, hintId].filter(Boolean).join(" ") || undefined;
  const shared = {
    id: name,
    name,
    value,
    required,
    disabled,
    placeholder,
    "aria-describedby": describedBy,
    "aria-invalid": error ? true : undefined,
  };

  return (
    <div className="grid gap-2">
      <Label htmlFor={name} className="text-base font-medium">
        {label}
      </Label>
      {multiline ? (
        <Textarea
          {...shared}
          autoFocus={autoFocus}
          onChange={(event) => onChange(event.target.value)}
        />
      ) : (
        <Input
          {...shared}
          type={type}
          autoComplete={autoComplete}
          autoFocus={autoFocus}
          minLength={minLength}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
      {error ? (
        <p id={errorId} className="flex items-start gap-1.5 text-sm text-destructive">
          <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          {error}
        </p>
      ) : null}
      {hint ? (
        <p id={hintId} className="text-sm text-muted-foreground">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

/** The spinner that replaces a button's label while it is working. */
export function Busy({ children }: { children: React.ReactNode }) {
  return (
    <>
      <Loader2 aria-hidden="true" className="animate-spin" />
      {children}
    </>
  );
}

/**
 * Whether there is a session, which only the server can say: the cookie is
 * HttpOnly and this code cannot read it. Without one the reader is sent to
 * sign in — a page is not an authorisation, and the routes it would call
 * would refuse it anyway.
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

/**
 * What stands in while the session question is out, and while the reader is
 * being sent away: the shape of the page rather than a spinner, so the
 * layout does not jump when the content lands.
 */
export function SessionPending() {
  return (
    <main id="main" className="container-narrow py-12">
      <span className="sr-only" role="status">
        Checking your session…
      </span>
      <div className="space-y-4">
        <Skeleton className="h-9 w-48" />
        <Skeleton className="h-5 w-full max-w-sm" />
        <Skeleton className="h-44 w-full rounded-xl" />
      </div>
    </main>
  );
}
