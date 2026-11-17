"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
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
import { api, type AppInfo } from "@/lib/api";
import {
  MAX_UPLOAD_FALLBACK_BYTES,
  documentNameFor,
  looksLikePdf,
  readableSize,
  uploadProblem,
} from "@/lib/send";
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
  // Why the chosen file cannot be signed, or null. One gate for both checks
  // — the synchronous name-and-size one and the asynchronous first-five-bytes
  // one — so the button can never contradict the message above it. Before
  // this, the bytes check only *said* the file was not a PDF: the button
  // stayed enabled and sent it anyway, and the server's 400 was the first
  // thing that actually stopped it.
  const [blocked, setBlocked] = useState<string | null>(null);
  // Which choice the asynchronous check belongs to. Choosing a second file
  // while the first one's bytes are still being read must not let the first
  // file's verdict land on the second.
  const choice = useRef(0);
  // The ceiling the server actually enforces. Read from the server rather
  // than hard-coded here, so a deployment that raises SIGNER_UPLOAD_MAX_BYTES
  // does not leave this screen refusing files the server would accept
  // (finding S-07). The fallback covers the moment before the answer lands.
  const [app, setApp] = useState<AppInfo | null>(null);
  const maxBytes = app?.maxUploadBytes ?? MAX_UPLOAD_FALLBACK_BYTES;

  useEffect(() => {
    let alive = true;
    api.appInfo().then((result) => {
      if (alive && result.ok && result.body) setApp(result.body);
    });
    return () => {
      alive = false;
    };
  }, []);

  function chooseFile(chosen: File | null) {
    const mine = ++choice.current;
    setFile(chosen);
    setBlocked(null);
    clear();
    setPreviewUrl((old) => {
      if (old) URL.revokeObjectURL(old);
      return chosen ? URL.createObjectURL(chosen) : null;
    });
    // Say what is wrong with the choice now, while the reader is still
    // looking at the file they picked — not after a hundred megabytes have
    // gone up the wire and come back as a 413.
    if (chosen) {
      const problem = uploadProblem(chosen, maxBytes);
      if (problem) {
        say(problem, "bad");
        setBlocked(problem);
        return;
      }
      // And read just the first five bytes: a text file renamed .pdf is the
      // one mistake the name cannot catch. The server checks this too, and
      // its answer is the one that counts (finding S-03).
      chosen
        .slice(0, 5)
        .arrayBuffer()
        .then((head) => {
          // A later choice has already replaced this one: its verdict is the
          // only one that may speak.
          if (choice.current !== mine) return;
          if (!looksLikePdf(new Uint8Array(head))) {
            const problem =
              "That file is named .pdf but is not a PDF document. Choose the PDF you want signed.";
            say(problem, "bad");
            setBlocked(problem);
          }
        })
        .catch(() => {
          // Unreadable here is not a verdict: let the server decide.
        });
    }
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
    if (!me) {
      return;
    }
    // Whatever either check found stands. The button is disabled in this
    // state, so reaching here means something raced; refusing is still the
    // honest answer.
    if (blocked) {
      say(blocked, "bad");
      return;
    }
    const problem = uploadProblem(file, maxBytes);
    if (problem) {
      say(problem, "bad");
      setBlocked(problem);
      return;
    }
    const documentName = documentNameFor(file!.name);
    if (!documentName) {
      say("That file has no name the signed PDF could keep.", "bad");
      return;
    }
    setBusy(true);
    say("Preparing…");
    api
      .createWorkflow(documentName, "SEQUENTIAL",
        [{ order: 1, userId: me.username, certificateSha256: null }], file!)
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
            if (busy) return;
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            // The file input is disabled while the document is being
            // prepared, but a drop does not go through it: without this, a
            // second PDF could replace the one already on its way up.
            if (busy) return;
            chooseFile(event.dataTransfer.files?.[0] ?? null);
          }}
          className={cn(
            "flex flex-col items-center gap-3 rounded-xl border-2 border-dashed p-10 text-center transition-colors",
            // The input inside is `sr-only`, so its own focus ring is
            // invisible and a keyboard reader was landing here with nothing
            // to see. The ring belongs on what is actually on the screen.
            "focus-within:outline-none focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2 focus-within:ring-offset-background",
            busy ? "cursor-not-allowed opacity-60" : "cursor-pointer",
            dragging ? "border-primary bg-accent" : "border-border bg-muted",
          )}
        >
          <Upload aria-hidden="true" className="size-8 text-muted-foreground" />
          <span className="text-base font-medium">
            {file ? "Choose a different PDF" : "Drop a PDF here, or click to choose one"}
          </span>
          <span className="text-sm text-muted-foreground">
            PDF only, up to {readableSize(maxBytes)}.
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
              {/* The size changes with every file chosen, so it is the one
                  number here that visibly re-renders in place. */}
              <p className="text-sm tabular-nums text-muted-foreground">
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
          disabled={busy || !file || blocked !== null}
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
