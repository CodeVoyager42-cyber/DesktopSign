"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  CheckCircle2,
  CircleSlash,
  CreditCard,
  Download,
  ExternalLink,
  FileCheck2,
  Loader2,
  MonitorX,
  TimerOff,
} from "lucide-react";
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
import { api, type AppInfo, type AuditEntry, type Workflow } from "@/lib/api";
import {
  FOCUS_GRACE_MS,
  TRUST_WORDS,
  appNotDetected,
  clock,
  outcomeFor,
  pollDelay,
  pollExhausted,
  refusalWords,
  secondsLeft,
  settled,
  signedAt,
  worthRetrying,
  type Outcome,
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
  // What became of the document, as the server reports it. This is what the
  // page is really waiting for: the code only opens the app, the SIGNATURE
  // is what finishes.
  const [outcome, setOutcome] = useState<Outcome>("waiting");
  const [audit, setAudit] = useState<AuditEntry[] | null>(null);
  const [gaveUp, setGaveUp] = useState(false);

  // The code itself lives here and nowhere else: not in state that renders,
  // not in the DOM, not in a log line.
  const issued = useRef<{ at: number; lifetime: number } | null>(null);
  // Focus moves to the heading when the page reaches a terminal state, so
  // a screen reader lands on the outcome rather than staying where the
  // spinner used to be.
  const successHeading = useRef<HTMLHeadingElement>(null);
  const mounted = useRef(true);
  useEffect(() => () => {
    mounted.current = false;
  }, []);

  // ── Did the application actually open? ──────────────────────────────
  // A browser cannot ask "is signflow:// registered?", but a launch that
  // works makes itself heard: the opening application (or the accepted
  // "open this?" dialog) takes focus away from this tab — a window blur,
  // or the document becoming hidden. So: listen for that, and if the
  // grace window passes in silence, say the truth — the app did not open,
  // probably because it is not installed — and put the install link
  // first, with Try again beside it, never a dead end.
  const probe = useRef<{ sawLeave: boolean }>({ sawLeave: false });
  const stopProbe = useRef<() => void>(() => {});
  useEffect(() => () => stopProbe.current(), []);

  const startProbe = useCallback(() => {
    stopProbe.current();
    probe.current.sawLeave = false;
    const mark = () => {
      probe.current.sawLeave = true;
    };
    const onVisibility = () => {
      if (document.hidden) mark();
    };
    window.addEventListener("blur", mark);
    document.addEventListener("visibilitychange", onVisibility);
    const timer = window.setTimeout(() => {
      window.removeEventListener("blur", mark);
      document.removeEventListener("visibilitychange", onVisibility);
      // The grace window is exactly how long the listener ran, so the
      // elapsed time is the constant itself — the same number the pure
      // function is tested with.
      if (appNotDetected(probe.current.sawLeave, FOCUS_GRACE_MS)) {
        setPhase("missing");
      }
    }, FOCUS_GRACE_MS);
    stopProbe.current = () => {
      window.removeEventListener("blur", mark);
      document.removeEventListener("visibilitychange", onVisibility);
      clearTimeout(timer);
    };
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
      // Whether or not the navigation threw, the page now listens for the
      // application taking the focus; silence says "not installed".
      startProbe();
    });
  }, [say, startProbe]);

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

  // ── Waiting for the signature ───────────────────────────────────────
  // The desktop signs and submits; this page finds out by asking. One timer,
  // rescheduled after each answer rather than a fixed interval, so a slow or
  // failing reply cannot pile requests on top of each other. It keeps asking
  // through "expired" and "missing" on purpose: a code that was already
  // consumed is still being signed with, and the workflow moving on is the
  // better evidence than either of those guesses.
  useEffect(() => {
    if (!me || !id || settled(outcome) || gaveUp) return;

    let alive = true;
    let timer = 0;
    const startedAt = Date.now();
    let failures = 0;

    const ask = () => {
      api.workflow(id).then((result) => {
        if (!alive) return;
        if (!result.ok || !result.body) {
          // A refusal is an answer: stop. A network failure is not, so back
          // off and keep asking.
          if (result.status === 0 || result.status >= 500) {
            failures += 1;
          } else {
            setGaveUp(true);
            return;
          }
        } else {
          failures = 0;
          setWorkflow(result.body);
          const next = outcomeFor(result.body.status);
          setOutcome(next);
          if (next === "signed") {
            // Only now, and only once: the trail is read for the time the
            // signature was accepted, which the workflow view does not carry.
            api.audit(id).then((trail) => {
              if (alive && trail.ok) setAudit(trail.body ?? []);
            });
            return;
          }
          if (settled(next)) return;
        }
        if (pollExhausted(startedAt, Date.now())) {
          setGaveUp(true);
          return;
        }
        timer = window.setTimeout(ask, pollDelay(failures));
      });
    };

    timer = window.setTimeout(ask, pollDelay(0));
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [me, id, outcome, gaveUp]);

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

  // Signed. Terminal, and the only screen that offers the document back.
  if (outcome === "signed" && workflow) {
    const when = signedAt(audit);
    return (
      <AppShell me={me} narrow>
        <SectionCard className="text-center">
          <div className="mx-auto flex size-14 items-center justify-center rounded-full bg-success/10">
            <CheckCircle2 aria-hidden="true" className="size-7 text-success-strong" />
          </div>
          <div className="space-y-2">
            <h1 className="text-2xl" tabIndex={-1} ref={successHeading}>
              Signed
            </h1>
            <p className="break-words text-base font-medium">{workflow.documentName}</p>
          </div>
          <p role="status" aria-live="polite" className="sr-only">
            The document is signed and ready to download.
          </p>

          <dl className="mx-auto max-w-sm space-y-2 text-left text-sm">
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">Signed by</dt>
              <dd className="font-medium">{me.displayName}</dd>
            </div>
            {when ? (
              <div className="flex justify-between gap-4">
                <dt className="text-muted-foreground">When</dt>
                <dd className="font-medium">{new Date(when).toLocaleString()}</dd>
              </div>
            ) : null}
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">Signatures</dt>
              <dd className="font-medium">
                {workflow.completedSignatures} of {workflow.totalSignatures}
              </dd>
            </div>
          </dl>

          {/* Finding F-01 is open: the server checks the signature over the
              bytes and the certificate bound to the slot, but validates that
              certificate against no trust anchor and sends this page no
              verdict. So this says what is true and claims nothing more. */}
          <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <FileCheck2 aria-hidden="true" className="size-4" />
            {TRUST_WORDS}
          </p>

          <Button asChild size="lg" className="w-full">
            <a href={api.downloadUrl(workflow.workflowId)} download>
              <Download aria-hidden="true" />
              Download the signed PDF
            </a>
          </Button>
          <Links>
            <TextLink href="/send">Sign another document</TextLink>
            <TextLink href="/documents">My documents</TextLink>
          </Links>
        </SectionCard>
      </AppShell>
    );
  }

  // Terminal and not a signature: cancelled, or stopped by an error.
  if (outcome === "stopped" && workflow) {
    return (
      <AppShell me={me} narrow>
        <SectionCard className="text-center">
          <div className="mx-auto flex size-14 items-center justify-center rounded-full bg-muted">
            <CircleSlash aria-hidden="true" className="size-7 text-muted-foreground" />
          </div>
          <div className="space-y-2">
            <h1 className="text-2xl" tabIndex={-1} ref={successHeading}>
              {workflow.status === "CANCELLED" ? "This document was cancelled" : "Signing stopped"}
            </h1>
            <p className="break-words text-base font-medium">{workflow.documentName}</p>
            {workflow.blockedReason ? (
              <p role="alert" className="text-base text-muted-foreground">
                {workflow.blockedReason}
              </p>
            ) : null}
          </div>
          <Links>
            <TextLink href="/send">Sign another document</TextLink>
            <TextLink href="/documents">My documents</TextLink>
          </Links>
        </SectionCard>
      </AppShell>
    );
  }

  return (
    <AppShell me={me} narrow>
      <SectionCard className="text-center">
        <div
          className={cn(
            "mx-auto flex size-14 items-center justify-center rounded-full",
            phase === "refused" && "bg-warning/10",
            phase === "missing" && "bg-warning/10",
            phase === "expired" && "bg-muted",
            working && "bg-accent",
          )}
        >
          {working ? (
            <Loader2 aria-hidden="true" className="size-7 animate-spin text-primary" />
          ) : phase === "expired" ? (
            <TimerOff aria-hidden="true" className="size-7 text-muted-foreground" />
          ) : phase === "missing" ? (
            <MonitorX aria-hidden="true" className="size-7 text-warning-strong" />
          ) : (
            <CreditCard aria-hidden="true" className="size-7 text-warning-strong" />
          )}
        </div>

        <div className="space-y-2">
          <h1 className="text-2xl">
            {working ? `Opening the ${appName} app…` : null}
            {phase === "missing" ? "The app did not open" : null}
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

        {phase === "missing" ? (
          <>
            <Alert className="text-left shadow-sm">
              <MonitorX aria-hidden="true" />
              <AlertTitle>Install the app first</AlertTitle>
              <AlertDescription className="text-base">
                Nothing opened, so the {appName} app is probably not installed
                on this computer yet. Install it below, then come back and
                press <span className="font-medium">Sign now</span> again.
                If a browser popup did ask to open the app, approve it and
                press Try again.
              </AlertDescription>
            </Alert>
            <Button asChild size="lg" className="w-full">
              <Link href="/download">
                <Download aria-hidden="true" />
                Install the {appName} app
              </Link>
            </Button>
            <Button
              type="button"
              size="lg"
              variant="outline"
              className="w-full"
              onClick={() => open(id!)}
            >
              Try again
            </Button>
            {app?.downloadUrl ? (
              <p className="text-sm">
                <Button asChild variant="link" className="h-auto px-0 text-sm">
                  {/* An absolute, configured URL: a real anchor, not a route. */}
                  <a href={app.downloadUrl}>
                    Or download the installer directly
                    <ExternalLink aria-hidden="true" />
                  </a>
                </Button>
              </p>
            ) : null}
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

        {gaveUp ? (
          <Alert role="alert">
            <AlertTitle>Still nothing after ten minutes</AlertTitle>
            <AlertDescription>
              This page has stopped checking. If you signed in the {appName}
              app the document is safe — open it from My documents to see
              where it stands.
            </AlertDescription>
          </Alert>
        ) : null}

        <NoteAlert note={note} />

        {phase !== "missing" ? (
          <div className="border-t border-border pt-4">
            <p className="text-base text-muted-foreground">Nothing happened?</p>
            {/* The download page, always — it explains the install, the
                SmartScreen warning and the card reader, which a bare link to a
                file does not. It is public, so it works before the reader has
                the application that the signing itself happens in. */}
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
        ) : null}
      </SectionCard>

      <div className="mt-6">
        <Links>
          <TextLink href="/documents">Back to my documents</TextLink>
        </Links>
      </div>
    </AppShell>
  );
}
