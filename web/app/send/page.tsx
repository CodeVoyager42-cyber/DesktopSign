"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { FileText, PenLine, Upload } from "lucide-react";
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
import { documentNameFor, readableSize } from "@/lib/send";
import { cn } from "@/lib/utils";

/**
 * Signing a document — one screen, and the signer is you.
 *
 * The three-step wizard this screen replaces asked who else should be
 * invited and in what order, and every e-mail-addressed answer put an
 * invitation e-mail on the server's outbox. None of that is here any more:
 * the only signer of the document is the account that uploads it, so the
 * server has no invitation to send and nobody waits for an e-mail. Upload,
 * name it, and the document screen's <b>Sign now</b> hands the signing to
 * the desktop application on this computer.
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
    if (!documentName.trim() || !me) {
      say("Give the document a name.", "bad");
      return;
    }
    setBusy(true);
    say("Preparing…");
    // One signer: the account reading this screen. A signer named by
    // username (no `@`) is a direct slot on the server — no invitation
    // row, no e-mail, nothing in an outbox.
    api
      .createWorkflow(documentName.trim(), "SEQUENTIAL",
        [{ order: 1, userId: me.username, certificateSha256: null }], file)
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

        <div className="flex items-center gap-3 rounded-xl border border-border bg-muted p-4">
          <Initials of={me.displayName} />
          <div className="min-w-0">
            <p className="font-medium">{me.displayName}</p>
            <p className="text-sm text-muted-foreground">
              You are the signer. No invitation is sent — the next step opens
              the app for your signature.
            </p>
          </div>
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
