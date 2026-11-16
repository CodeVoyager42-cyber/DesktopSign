"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { FileText, PenLine, Plus, Upload, X } from "lucide-react";
import {
  Initials,
  Links,
  PageHeader,
  SectionCard,
  TextLink,
} from "@/components/blocks";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import {
  coSignerProblem,
  documentNameFor,
  POLICIES,
  problemWith,
  readableSize,
  signersFrom,
} from "@/lib/send";
import { cn } from "@/lib/utils";

/**
 * Sending a document for signature — one screen. You are always signer 1,
 * and you may add the people who sign after you.
 *
 * The three-step wizard this screen replaces asked who else should be
 * invited, and every e-mail-addressed answer put an invitation e-mail on the
 * server's outbox. That is gone and is not coming back: a signer is named by
 * their SignFlow <b>username</b>, which means they must already have an
 * account. They make one themselves — sign-up is open — and the server
 * refuses a name it cannot find rather than accepting a workflow that would
 * wait for ever on somebody who does not exist.
 *
 * With more than one signer the order matters, so the policy choice appears:
 * one after another, or any order. With only you it does not, and it stays
 * hidden. Upload, name it, add anyone else, and the document screen's
 * <b>Sign now</b> hands your own signature to the desktop application.
 *
 * Nothing about what reaches the server has changed otherwise: the same
 * multipart create call, the same `.pdf`-only rule, the same document name
 * defaulted from the file. The preview is a local blob URL — the document
 * bytes never leave this computer before the reader says so.
 */
export default function Send() {
  const { me, state } = useSession();
  const { note, say, clear } = useNote();
  const [file, setFile] = useState<File | null>(null);
  // A local blob URL of the chosen PDF, so the reader sees the pages they
  // are about to sign before the desktop app is asked to open. It is
  // revoked the moment the file changes or the screen goes away.
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [documentName, setDocumentName] = useState("");
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  // Co-signers, by username. The reader is always signer 1; these are the
  // people after them. Usernames, not addresses: there is no invitation to
  // send, so a signer must already have an account (the server refuses a
  // name it cannot find, and says so).
  const [others, setOthers] = useState<string[]>([]);
  const [policy, setPolicy] = useState("SEQUENTIAL");

  function chooseFile(chosen: File | null) {
    setFile(chosen);
    clear();
    setDocumentName(documentNameFor(chosen?.name ?? ""));
    setPreviewUrl((old) => {
      if (old) URL.revokeObjectURL(old);
      return chosen ? URL.createObjectURL(chosen) : null;
    });
  }

  // The blob URL is this screen's own resource: released when the screen
  // goes away, so the browser does not keep the document bytes for the
  // life of the tab.
  useEffect(
    () => () => {
      setPreviewUrl((current) => {
        if (current) URL.revokeObjectURL(current);
        return null;
      });
    },
    [],
  );

  function send() {
    if (!file) {
      say("Choose the PDF you want signed.", "bad");
      return;
    }
    if (!file.name.toLowerCase().endsWith(".pdf")) {
      say("Only PDF documents can be signed.", "bad");
      return;
    }
    if (!me) {
      return;
    }
    // Signer 1 is always the reader; the rest are the rows they added.
    const rows = [me.username, ...others];
    const problem = problemWith(file, documentName, rows) ?? coSignerProblem(rows);
    if (problem) {
      say(problem, "bad");
      return;
    }
    setBusy(true);
    say("Preparing…");
    // Username slots are direct slots on the server: nothing is e-mailed to
    // anybody, and each signer finds the document under their signing tasks.
    api
      .createWorkflow(documentName.trim(), others.length > 0 ? policy : "SEQUENTIAL",
        signersFrom(rows), file)
      .then((result) => {
        if (!result.ok || !result.body) {
          say(
            result.status === 429
              ? "Too many attempts — please wait a few minutes and try again."
              : // The server's own message: it knows which address is
                // malformed, which account does not exist and how large a
                // file may be.
                (result.message ?? "The document could not be prepared."),
            "bad",
          );
          setBusy(false);
          return;
        }
        window.location.assign(
          `/document?id=${encodeURIComponent(result.body.workflow.workflowId)}`,
        );
      });
  }

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  // The server refuses to create a document for an account that has not
  // confirmed its address. Saying so here beats a form that fills in and
  // then fails.
  if (!me.emailVerified) {
    return (
      <AppShell me={me} narrow>
        <PageHeader
          title="Confirm your email first"
          description="Signing needs a confirmed email address for your account."
        />
        <SectionCard>
          <p className="text-base text-muted-foreground">
            Your account screen can send the confirmation link again.
          </p>
          <Button asChild size="lg" className="w-full">
            <Link href="/account">Go to my account</Link>
          </Button>
        </SectionCard>
      </AppShell>
    );
  }

  return (
    <AppShell me={me} narrow>
      <PageHeader title="Sign a document">
        <p className="pt-2 text-base text-muted-foreground">
          You sign it yourself. After this screen the SignFlow app on this
          computer does the signing — the website never touches your card.
        </p>
      </PageHeader>

      <SectionCard>
        <div
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            chooseFile(event.dataTransfer.files?.[0] ?? null);
          }}
          className={cn(
            "rounded-xl border-2 border-dashed p-6 text-center transition-colors duration-200",
            dragging ? "border-primary bg-accent" : "border-border bg-muted",
          )}
        >
          <Upload aria-hidden="true" className="mx-auto size-8 text-muted-foreground" />
          <p className="mt-3 text-base font-medium">
            Drag your PDF here, or choose it
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            A PDF. Nothing else can be signed.
          </p>
          <div className="mx-auto mt-4 max-w-xs">
            <Label htmlFor="file" className="sr-only">
              Choose a PDF
            </Label>
            <Input
              id="file"
              name="file"
              type="file"
              accept="application/pdf,.pdf"
              className="cursor-pointer bg-background file:mr-3 file:font-medium"
              onChange={(event) => chooseFile(event.target.files?.[0] ?? null)}
            />
          </div>
        </div>

        {file ? (
          <>
            <p className="flex items-center gap-2 rounded-lg bg-success/10 p-3 text-base text-success-strong">
              <FileText aria-hidden="true" className="size-5 shrink-0" />
              <span className="break-all font-medium">{file.name}</span>
              <span className="ml-auto shrink-0 text-sm">
                {readableSize(file.size)}
              </span>
            </p>
            {previewUrl ? (
              <div className="overflow-hidden rounded-lg border bg-muted">
                {/* The browser's own PDF viewer, pointed at a local
                    blob URL: the document bytes never leave this
                    computer, so a preview here is not a second copy
                    on a server anywhere. */}
                <iframe
                  src={previewUrl}
                  title={`Preview of ${documentName || file.name}`}
                  className="h-[420px] w-full bg-background"
                />
              </div>
            ) : null}
          </>
        ) : null}

        <Field
          label="Document name"
          name="documentName"
          value={documentName}
          onChange={setDocumentName}
          hint="What the signed PDF is downloaded as."
          disabled={busy}
        />

        <div className="space-y-3">
          <div className="flex items-center gap-3 rounded-xl border border-border bg-muted p-4">
            <Initials of={me.displayName} />
            <div className="min-w-0">
              <p className="font-medium">
                {me.displayName}{" "}
                <span className="text-sm font-normal text-muted-foreground">
                  — signer 1, you
                </span>
              </p>
              <p className="text-sm text-muted-foreground">
                The next step opens the app for your signature.
              </p>
            </div>
          </div>

          {others.map((who, index) => (
            <div key={index} className="flex items-end gap-2">
              <div className="min-w-0 flex-1 space-y-1.5">
                <Label htmlFor={`signer-${index}`}>
                  Signer {index + 2} — their SignFlow username
                </Label>
                <Input
                  id={`signer-${index}`}
                  value={who}
                  autoComplete="off"
                  placeholder="the username they signed up with"
                  disabled={busy}
                  onChange={(event) =>
                    setOthers((rows) =>
                      rows.map((row, i) => (i === index ? event.target.value : row)),
                    )
                  }
                />
              </div>
              <Button
                type="button"
                variant="ghost"
                disabled={busy}
                aria-label={`Remove signer ${index + 2}`}
                onClick={() =>
                  setOthers((rows) => rows.filter((_, i) => i !== index))
                }
              >
                <X aria-hidden="true" />
              </Button>
            </div>
          ))}

          <Button
            type="button"
            variant="outline"
            disabled={busy}
            onClick={() => {
              clear();
              setOthers((rows) => [...rows, ""]);
            }}
          >
            <Plus aria-hidden="true" />
            Add another signer
          </Button>

          <p className="text-sm text-muted-foreground">
            Anyone you add needs a SignFlow account already — there are no
            e-mail invitations. They sign up themselves, then you add them by
            the username they chose.
          </p>

          {others.length > 0 ? (
            <fieldset className="space-y-2 rounded-xl border border-border p-4">
              <legend className="px-1 text-sm font-medium">Signing order</legend>
              {POLICIES.map((option) => (
                <label
                  key={option.value}
                  className="flex items-start gap-3 text-sm"
                  htmlFor={`policy-${option.value}`}
                >
                  <input
                    id={`policy-${option.value}`}
                    type="radio"
                    name="policy"
                    className="mt-1"
                    value={option.value}
                    checked={policy === option.value}
                    disabled={busy}
                    onChange={() => setPolicy(option.value)}
                  />
                  <span>
                    <span className="font-medium">{option.label}</span>
                    <span className="block text-muted-foreground">{option.hint}</span>
                  </span>
                </label>
              ))}
            </fieldset>
          ) : null}
        </div>

        <NoteAlert note={note} />

        <Button
          type="button"
          size="lg"
          className="w-full"
          disabled={busy || !file}
          onClick={send}
        >
          {busy ? (
            <Busy>Preparing…</Busy>
          ) : (
            <>
              <PenLine aria-hidden="true" />
              Sign this document
            </>
          )}
        </Button>
      </SectionCard>

      <div className="mt-6">
        <Links>
          <TextLink href="/documents">Cancel and go back to my documents</TextLink>
        </Links>
      </div>
    </AppShell>
  );
}
