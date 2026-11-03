"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowRight, FileText, Upload } from "lucide-react";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { documentNameFor, problemWith } from "@/lib/upload";
import { cn } from "@/lib/utils";

/**
 * Uploading a document to sign.
 *
 * One screen, because there is one question: which PDF? The account that
 * uploads it is its only signer, so there is nobody to name, no order to
 * choose and nothing sent to anybody. On success it goes to the document's
 * own screen, where the server's audit trail is the receipt — a success
 * panel here would be a second account of the same event, and the one that
 * goes stale.
 */

export default function UploadDocument() {
  const { me, state } = useSession();
  const { note, say, clear } = useNote();
  const [file, setFile] = useState<File | null>(null);
  const [documentName, setDocumentName] = useState("");
  // Whether the reader has typed a name of their own. Choosing another file
  // should replace a defaulted name and never overwrite a deliberate one.
  const [named, setNamed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);

  function chooseFile(chosen: File | null) {
    setFile(chosen);
    clear();
    if (chosen && !named) {
      setDocumentName(documentNameFor(chosen.name));
    }
  }

  function upload() {
    const problem = problemWith(file, documentName);
    if (problem || !file) {
      say(problem ?? "Choose the PDF you want to sign.", "bad");
      return;
    }
    setBusy(true);
    say("Uploading…");
    api.createWorkflow(documentName.trim(), file).then((result) => {
      if (!result.ok || !result.body) {
        say(
          result.status === 429
            ? "Too many attempts — please wait a few minutes and try again."
            : // The server's own message: it knows how large a file may be.
              (result.message ?? "The document could not be uploaded."),
          "bad",
        );
        setBusy(false);
        return;
      }
      window.location.assign(
        `/document?id=${encodeURIComponent(result.body.workflowId)}`,
      );
    });
  }

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  // The server refuses to prepare a document for an account that has not
  // confirmed its address. Saying so here beats a form that fills in and
  // then fails.
  if (!me.emailVerified) {
    return (
      <AppShell me={me} narrow>
        <PageHeader
          title="Confirm your email first"
          description="Signing a document needs a confirmed email address."
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
      <PageHeader
        title="Sign a document"
        description="Choose the PDF. It is prepared for your signature, and you sign it in the SignFlow app on this computer with your own card."
      />

      <SectionCard title="Choose the PDF">
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
          <p className="flex items-center gap-2 rounded-lg bg-success/10 p-3 text-base text-success-strong">
            <FileText aria-hidden="true" className="size-5 shrink-0" />
            <span className="break-all font-medium">{file.name}</span>
          </p>
        ) : null}

        <Field
          label="Document name"
          name="documentName"
          value={documentName}
          onChange={(value) => {
            setNamed(true);
            setDocumentName(value);
          }}
          hint="What you will see it called, and what it is downloaded as."
          disabled={busy}
        />

        <NoteAlert note={note} />

        <Button
          type="button"
          size="lg"
          className="w-full"
          disabled={busy}
          onClick={upload}
        >
          {busy ? (
            <Busy>Uploading…</Busy>
          ) : (
            <>
              Upload and continue
              <ArrowRight aria-hidden="true" />
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
