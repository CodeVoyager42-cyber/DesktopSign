"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { CreditCard, Download, ExternalLink, Loader2, TimerOff } from "lucide-react";
import { Links, PageHeader, SectionCard, TextLink } from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import {
  NoteAlert,
  SessionPending,
  useNote,
  useSession,
} from "@/components/session";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { api, type AppInfo, type Workflow } from "@/lib/api";
import {
  clock,
  refusalWords,
  secondsLeft,
  worthRetrying,
  type Phase,
} from "@/lib/handoff";
import { cn } from "@/lib/utils";

/**
 * The hand-off: the screen that opens the SignFlow app on this computer, at
 * this one document.
 *
 * <b>The logic here is unchanged and must stay that way.</b> The code is
 * never put in the DOM — the page navigates by assigning `location.href` and
 * holds the link in a ref — and no code is ever reused: when the clock runs
 * out the screen asks for a new one. An `<a href={deepLink}>` would put a
 * credential where a right-click can copy it. Only the look is new.
 *
 * It tries once on arrival (the reader clicked something to get here) and
 * then offers a button, because navigating to a custom scheme is privileged:
 * a browser may ignore it when no click caused it.
 */
export default function Open() {
  const { me, state } = useSession();
  const { note, say } = useNote();
  const [id, setId] = useState<string | null | undefined>(undefined);
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [app, setApp] = useState<AppInfo | null>(null);
  const [phase, setPhase] = useState<Phase>("asking");
  const [left, setLeft] = useState<number>(0);
  const [refusal, setRefusal] = useState<{ code: string | null; message: string | null }>(
    { code: null, message: null },
  );

  // The code itself lives here and nowhere else: not in state that renders,
  // not in the DOM, not in a log line.
  const issued = useRef<{ at: number; lifetime: number } | null>(null);
  const mounted = useRef(true);
  useEffect(() => () => {
    mounted.current = false;
  }, []);

  useEffect(() => {
    setId(new URLSearchParams(window.location.search).get("id"));
  }, []);

  const open = useCallback((workflowId: string) => {
    setPhase("asking");
    api.launchCode(workflowId).then((result) => {
      if (!mounted.current) return;
      if (!result.ok || !result.body) {
        setRefusal({ code: result.code, message: result.message });
        setPhase("refused");
        return;
      }
      const lifetime = result.body.expiresInSeconds;
      issued.current = { at: Date.now(), lifetime };
      setLeft(lifetime);
      setPhase("opening");
      try {
        window.location.href = result.body.deepLink;
      } catch {
        say("This browser would not open the app by itself. Use the button below.");
      }
    });
  }, [say]);

  useEffect(() => {
    if (!me || !id) return;
    api.workflow(id).then((result) => {
      if (mounted.current && result.ok && result.body) setWorkflow(result.body);
    });
    api.appInfo().then((result) => {
      if (mounted.current && result.ok && result.body) setApp(result.body);
    });
    open(id);
  }, [me, id, open]);

  // One tick a second while a code is live, read from the issue time rather
  // than counted down in state: a tab that was asleep for a minute comes
  // back with the right answer instead of a stale one.
  useEffect(() => {
    if (phase !== "opening") return;
    const tick = setInterval(() => {
      const code = issued.current;
      if (!code) return;
      const remaining = secondsLeft(code.at, code.lifetime, Date.now());
      setLeft(remaining);
      if (remaining === 0) {
        issued.current = null;
        setPhase("expired");
      }
    }, 1000);
    return () => clearInterval(tick);
  }, [phase]);

  useEffect(() => {
    document.title = workflow ? `Signing ${workflow.documentName}` : "SignFlow";
  }, [workflow]);

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  if (id === null) {
    return (
      <AppShell me={me} narrow>
        <PageHeader
          title="That link is incomplete"
          description="It does not say which document to sign."
        />
        <SectionCard>
          <Button asChild size="lg" className="w-full">
            <Link href="/documents">My documents</Link>
          </Button>
        </SectionCard>
      </AppShell>
    );
  }

  const appName = app?.appName ?? "SignFlow";
  const working = phase === "asking" || phase === "opening";

  return (
    <AppShell me={me} narrow>
      <SectionCard className="text-center">
        <div
          className={cn(
            "mx-auto flex size-14 items-center justify-center rounded-full",
            phase === "refused" && "bg-warning/10",
            phase === "expired" && "bg-muted",
            working && "bg-accent",
          )}
        >
          {working ? (
            <Loader2 aria-hidden="true" className="size-7 animate-spin text-primary" />
          ) : phase === "expired" ? (
            <TimerOff aria-hidden="true" className="size-7 text-muted-foreground" />
          ) : (
            <CreditCard aria-hidden="true" className="size-7 text-warning-strong" />
          )}
        </div>

        <div className="space-y-2">
          <h1 className="text-2xl">
            {working ? `Opening the ${appName} app…` : null}
            {phase === "expired" ? "The signing link timed out" : null}
            {phase === "refused" ? "This cannot be opened for signing" : null}
          </h1>
          {working ? (
            <p className="text-base text-muted-foreground">
              Insert your smart card.
            </p>
          ) : null}
          {workflow ? (
            <p className="break-words text-base font-medium">
              {workflow.documentName}
            </p>
          ) : null}
        </div>

        {phase === "opening" ? (
          <>
            <p role="status" aria-live="polite" className="text-base text-muted-foreground">
              <span className="mr-2 text-2xl font-semibold tabular-nums text-foreground">
                {clock(left)}
              </span>
              left to use this link
            </p>
            <p className="text-base text-muted-foreground">
              Signing happens in the app, on this computer — the certificate
              never leaves your card, and this website never sees it.
            </p>
            <Button type="button" size="lg" className="w-full" onClick={() => open(id!)}>
              Didn&apos;t open? Try again
            </Button>
          </>
        ) : null}

        {phase === "expired" ? (
          <>
            <p className="text-base text-muted-foreground">
              A signing link is good for two minutes. The next one is new —
              never the same link again.
            </p>
            <Button type="button" size="lg" className="w-full" onClick={() => open(id!)}>
              Get a new link
            </Button>
          </>
        ) : null}

        {phase === "refused" ? (
          <>
            <Alert variant="destructive" className="text-left shadow-sm">
              <CreditCard aria-hidden="true" />
              <AlertTitle>What to do</AlertTitle>
              <AlertDescription className="text-base">
                {refusal.code === "TOO_MANY_REQUESTS"
                  ? "Too many attempts — please wait a few minutes and try again."
                  : refusalWords(refusal.message)}
              </AlertDescription>
            </Alert>
            {worthRetrying(refusal.code) ? (
              <Button type="button" size="lg" className="w-full" onClick={() => open(id!)}>
                Try again
              </Button>
            ) : null}
          </>
        ) : null}

        <NoteAlert note={note} />

        <div className="border-t border-border pt-4">
          <p className="text-base text-muted-foreground">Nothing happened?</p>
          {/* The download page, always — it explains the install, the
              SmartScreen warning and the card reader, which a bare link to a
              file does not. It is public, so it works even for somebody who
              arrived here from an invitation and has no account. */}
          <Button asChild variant="outline" className="mt-3">
            <Link href="/download">
              <Download aria-hidden="true" />
              Get the {appName} app
            </Link>
          </Button>
          {app?.downloadUrl ? (
            <p className="mt-3 text-sm">
              <Button asChild variant="link" className="h-auto px-0 text-sm">
                {/* An absolute, configured URL: a real anchor, not a route. */}
                <a href={app.downloadUrl}>
                  Or download the installer directly
                  <ExternalLink aria-hidden="true" />
                </a>
              </Button>
            </p>
          ) : null}
        </div>
      </SectionCard>

      <div className="mt-6">
        <Links>
          <TextLink href={`/document?id=${encodeURIComponent(id ?? "")}`}>
            Back to the document
          </TextLink>
        </Links>
      </div>
    </AppShell>
  );
}
