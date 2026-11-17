"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { FileText, PenLine, Upload, X } from "lucide-react";
import { SectionCard } from "@/components/blocks";
import { Busy, NoteAlert, useNote } from "@/components/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { api, type AppInfo, type Profile } from "@/lib/api";
import {
  MAX_UPLOAD_FALLBACK_BYTES,
  documentNameFor,
  looksLikePdf,
  readableSize,
  uploadProblem,
} from "@/lib/send";
import { cn } from "@/lib/utils";

/**
 * Choose a PDF and sign it: the drop zone, the chosen file, and the one
 * button. The whole website, more or less.
 *
 * <p>Two steps and the second one leaves: choose the file, then press <b>Sign
 * it with your smart card</b>. That creates the signing task and hands
 * straight over to {@code /open}, which asks the server for a one-time launch
 * code and navigates to {@code signflow://launch/…} so the desktop
 * application opens on this document. The signature happens there, against
 * the card — this website never sees the key.
 *
 * <p><b>Why this is a component and not a page.</b> It was the body of
 * {@code /send}, which briefly shared it with {@code /documents} before
 * {@code /documents} was kept and {@code /send} removed — the two had become
 * the same screen. One caller again, then, and it stays a component for the
 * reason it became one: this is the file-size gate, the magic-byte check and
 * the drag-and-drop target, and a second screen that uploads a PDF should
 * reuse them rather than grow its own. The page supplies the
 * {@code AppShell} and the heading; everything below the heading is here.
 *
 * <p>The preview is a local blob URL: the document bytes stay on this
 * computer until the reader presses the button.
 */
export function UploadAndSign({ me }: { me: Profile }) {
  const { note, say, clear } = useNote();
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  // Why the chosen file cannot be signed, or null. One gate for both checks
  // — the synchronous name-and-size one and the asynchronous first-five-bytes
  // one — so the button can never contradict the message above it.
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

  return (
    <SectionCard
      title="Choose a PDF"
      icon={FileText}
      footer={
        <p className="text-sm text-muted-foreground">
          Signing opens the SignFlow app on this computer. Not installed yet?{" "}
          <Link href="/download" className="font-medium text-primary underline underline-offset-4">
            Get it here
          </Link>
          .
        </p>
      }
    >
      {/* `Label` rather than a bare <label>: it is the shadcn/Radix one every
          other field on the site uses, so the drop zone is the same kind of
          control as the rest. Its own classes set a 14px row, which is wrong
          for a target this size — the overrides below are deliberate and
          `cn` resolves them. */}
      <Label
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
          // The file input is disabled while the document is being prepared,
          // but a drop does not go through it: without this, a second PDF
          // could replace the one already on its way up.
          if (busy) return;
          chooseFile(event.dataTransfer.files?.[0] ?? null);
        }}
        className={cn(
          // `leading-normal` undoes Label's own `leading-none`, which is right
          // for a one-line field caption and cramped for three stacked lines.
          "flex w-full flex-col items-center gap-3 rounded-xl border-2 border-dashed px-6 py-10 text-center text-base leading-normal transition-colors sm:py-14",
          // The input inside is `sr-only`, so its own focus ring is invisible
          // and a keyboard reader was landing here with nothing to see. The
          // ring belongs on what is actually on the screen.
          "focus-within:outline-none focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2 focus-within:ring-offset-background",
          busy ? "cursor-not-allowed opacity-60" : "cursor-pointer",
          dragging
            ? "border-primary bg-accent"
            : "border-border bg-muted hover:border-primary/40 hover:bg-accent/40",
        )}
      >
        <span
          aria-hidden="true"
          className={cn(
            "flex size-12 items-center justify-center rounded-full transition-colors",
            dragging ? "bg-primary/10" : "bg-background",
          )}
        >
          <Upload className={cn("size-6", dragging ? "text-primary" : "text-muted-foreground")} />
        </span>
        <span className="text-base font-medium">
          {file ? "Choose a different PDF" : "Drop a PDF here, or click to choose one"}
        </span>
        {/* The ceiling is the server's, read from GET /web/api/app-info. Until
            that answers, a Skeleton rather than the fallback number: showing
            "100 MiB" and then correcting it to a deployment's real limit is
            worse than showing nothing for one moment. */}
        {app ? (
          <span className="text-sm tabular-nums text-muted-foreground">
            PDF only, up to {readableSize(maxBytes)}.
          </span>
        ) : (
          <Skeleton className="h-5 w-40" />
        )}
        {/* The shadcn `Input`, kept `sr-only`: the control has to exist and be
            focusable, but what the reader sees and drops onto is the zone
            around it. Its visible styling is clipped away, which is why the
            component's own `file:` classes do not matter here — the ring that
            does is `focus-within` on the Label. */}
        <Input
          id="pdf"
          type="file"
          accept="application/pdf,.pdf"
          className="sr-only"
          disabled={busy}
          onChange={(event) => chooseFile(event.target.files?.[0] ?? null)}
        />
      </Label>

      {file ? (
        <div className="rounded-xl border border-border bg-card p-4">
          <div className="flex items-start gap-3">
            <span
              aria-hidden="true"
              className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-accent"
            >
              <FileText className="size-5 text-accent-foreground" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-base font-medium">
                {documentNameFor(file.name)}
              </p>
              {/* The size changes with every file chosen, so it is the one
                  number here that visibly re-renders in place. */}
              <Badge variant="secondary" className="mt-1 tabular-nums">
                {readableSize(file.size)}
              </Badge>
            </div>
            {/* Clearing the choice was only possible by picking another file.
                `size="sm"` is 28px drawn and 44px to a finger — see the tap
                note in button.tsx. */}
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              disabled={busy}
              onClick={() => chooseFile(null)}
              aria-label="Remove the chosen PDF"
            >
              <X aria-hidden="true" />
            </Button>
          </div>
          {previewUrl ? (
            <>
              <Separator className="my-3" />
              <a
                href={previewUrl}
                target="_blank"
                rel="noreferrer"
                className="text-sm font-medium text-primary underline underline-offset-4"
              >
                Look at it first
              </a>
            </>
          ) : null}
        </div>
      ) : null}

      <NoteAlert note={note} />

      {/* Full width on a phone, where a wide target is the easier one to hit,
          and its own width from `sm` up, where a button stretched across a
          card reads as a banner rather than an action. */}
      <Button
        type="button"
        size="lg"
        className="w-full sm:w-auto"
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
    </SectionCard>
  );
}
