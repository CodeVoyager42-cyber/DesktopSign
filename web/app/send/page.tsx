"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { FileText, PenLine, Upload } from "lucide-react";
import { PageHeader } from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import {
  Busy,
  NoteAlert,
  SessionPending,
  useNote,
  useSession,
} from "@/components/session";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { documentNameFor, readableSize } from "@/lib/send";
import { cn } from "@/lib/utils";

/**
 * Upload a PDF and sign it. The whole website, more or less.
 *
 * One screen, two steps, and the second one leaves: choose the file, then
 * press <b>Sign it with your smart card</b>. That creates the signing task
 * and hands straight over to {@code /open}, which asks the server for a
 * one-time launch code and navigates to {@code signflow://launch/…} so the
 * desktop application opens on this document. The signature happens there,
 * against the card — this website never sees the key.
 *
 * <p>What this screen replaced, and why there is less of it: a "Send a
 * document" form that also asked for a document name, for co-signers by
 * username, and for a signing order. The name is the file's own name, which
 * is what the signed PDF is downloaded as anyway. The rest served a workflow
 * where somebody sends a document to other people; the product is somebody
 * signing their own, so the form asked questions with one answer. The server
 * still supports several signers — {@code POST /web/api/workflows} takes a
 * list — so adding that back is a screen, not a feature.
 *
 * <p>The preview is a local blob URL: the document bytes stay on this
 * computer until the reader presses the button.
 */
export default function Send() {
  const { me, state } = useSession();
  const { note, say, clear } = useNote();
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);

  function chooseFile(chosen: File | null) {
    setFile(chosen);
    clear();
    setPreviewUrl((old) => {
      if (old) URL.revokeObjectURL(old);
      return chosen ? URL.createObjectURL(chosen) : null;
    });
  }

  // The blob URL is this screen's own resource: released when the screen goes
  // away, so the browser does not hold the document for the life of the tab.
  useEffect(
    () => () => {
      setPreviewUrl((current) => {
        if (current) URL.revokeObjectURL(current);
        return null;
      });
    },
    [],
  );

  function signIt() {
    if (!file || !me) {
      say("Choose the PDF you want to sign.", "bad");
      return;
    }
    if (!file.name.toLowerCase().endsWith(".pdf")) {
      say("Only PDF documents can be signed.", "bad");
      return;
    }
    const documentName = documentNameFor(file.name);
    if (!documentName) {
      say("That file has no name the signed PDF could keep.", "bad");
      return;
    }
    setBusy(true);
    say("Preparing…");
    api
      .createWorkflow(documentName, "SEQUENTIAL",
        [{ order: 1, userId: me.username, certificateSha256: null }], file)
      .then((result) => {
        if (!result.ok || !result.body) {
          say(
            result.status === 429
              ? "Too many attempts — please wait a few minutes and try again."
              : // The server's own message: it knows how large a file may be
                // and what it will not accept.
                (result.message ?? "The document could not be prepared."),
            "bad",
          );
          setBusy(false);
          return;
        }
        // Straight to the hand-off. /open asks for the one-time code and
        // navigates to signflow://launch, and it is the screen that knows
        // what to say when nothing opens because the app is not installed.
        window.location.assign(
          `/open?id=${encodeURIComponent(result.body.workflow.workflowId)}`,
        );
      });
  }

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  return (
    <AppShell me={me}>
      <PageHeader title="Sign a document">
        <p className="pt-1 text-base text-muted-foreground">
          Choose a PDF, then sign it with your smart card. The signing happens
          in the app on this computer — the card never leaves your hands.
        </p>
      </PageHeader>

      <div className="space-y-6">
        <label
          htmlFor="pdf"
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
            "flex cursor-pointer flex-col items-center gap-3 rounded-xl border-2 border-dashed p-10 text-center transition-colors",
            dragging ? "border-primary bg-accent" : "border-border bg-muted",
          )}
        >
          <Upload aria-hidden="true" className="size-8 text-muted-foreground" />
          <span className="text-base font-medium">
            {file ? "Choose a different PDF" : "Drop a PDF here, or click to choose one"}
          </span>
          <span className="text-sm text-muted-foreground">
            Only PDF documents can be signed.
          </span>
          <input
            id="pdf"
            type="file"
            accept="application/pdf,.pdf"
            className="sr-only"
            disabled={busy}
            onChange={(event) => chooseFile(event.target.files?.[0] ?? null)}
          />
        </label>

        {file ? (
          <div className="flex items-center gap-3 rounded-xl border border-border p-4">
            <FileText aria-hidden="true" className="size-5 shrink-0" />
            <div className="min-w-0">
              <p className="truncate font-medium">{documentNameFor(file.name)}</p>
              <p className="text-sm text-muted-foreground">
                {readableSize(file.size)}
                {previewUrl ? (
                  <>
                    {" · "}
                    <a
                      href={previewUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="underline"
                    >
                      Look at it first
                    </a>
                  </>
                ) : null}
              </p>
            </div>
          </div>
        ) : null}

        <NoteAlert note={note} />

        <Button
          type="button"
          size="lg"
          className="w-full"
          disabled={busy || !file}
          onClick={signIt}
        >
          {busy ? (
            <Busy>Preparing…</Busy>
          ) : (
            <>
              <PenLine aria-hidden="true" />
              Sign it with your smart card
            </>
          )}
        </Button>

        <p className="text-center text-sm text-muted-foreground">
          Signing opens the SignFlow app. Not installed yet?{" "}
          <Link href="/download" className="underline">
            Get it here
          </Link>
          .
        </p>
      </div>
    </AppShell>
  );
}
