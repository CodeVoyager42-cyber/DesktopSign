"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ChevronDown,
  CircleSlash,
  Download,
  History,
  PenLine,
} from "lucide-react";
import {
  ConfirmDialog,
  PageHeader,
  SectionCard,
  TextLink,
} from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import {
  Busy,
  NoteAlert,
  SessionPending,
  useNote,
  useSession,
} from "@/components/session";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { api, type AuditEntry, type Workflow } from "@/lib/api";
import { FINISHED } from "@/lib/dashboard";
import { buildTimeline, type TimelineRow } from "@/lib/timeline";
import { cn } from "@/lib/utils";

/**
 * One document: where it stands and what happened to it, in order.
 *
 * The id is in the query string, not a path segment: the website is a static
 * export, a page cannot be built per document for ids created at runtime,
 * and the catch-all forward that would make a path segment work is the one
 * `FrontendRoutingConfiguration` refuses.
 *
 * The history is behind a disclosure because somebody opening this screen
 * wants to know whether their document is signed, and twenty rows of audit
 * trail is not that answer.
 */
function HistoryRow({ row }: { row: TimelineRow }) {
  return (
    <li className="relative border-l-2 border-border pb-5 pl-6 last:border-transparent last:pb-0">
      <span
        aria-hidden="true"
        className={cn(
          "absolute -left-[7px] top-1.5 size-3 rounded-full ring-4 ring-background",
          row.mark === "blocked" && "bg-warning",
          row.mark === "pending" && "border-2 border-border bg-background",
          row.mark === "done" && "bg-primary",
        )}
      />
      <p className={cn("text-base", row.mark === "pending" && "text-muted-foreground")}>
        {row.words}
      </p>
      {row.who || row.when ? (
        <p className="text-sm text-muted-foreground">
          {[row.who, row.when].filter(Boolean).join(" · ")}
        </p>
      ) : null}
    </li>
  );
}

export default function Document() {
  const { me, state } = useSession();
  const { note, say } = useNote();
  // Three states, not two: `undefined` is "the query string has not been
  // read yet" and `null` is "there is no id in it".
  const [id, setId] = useState<string | null | undefined>(undefined);
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [audit, setAudit] = useState<AuditEntry[] | null>(null);
  const [refused, setRefused] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [showHistory, setShowHistory] = useState(false);

  useEffect(() => {
    setId(new URLSearchParams(window.location.search).get("id"));
  }, []);

  const mounted = useRef(true);
  useEffect(() => () => {
    mounted.current = false;
  }, []);

  const load = useCallback(
    (workflowId: string) =>
      Promise.all([api.workflow(workflowId), api.audit(workflowId)]).then(
        ([one, trail]) => {
          if (!mounted.current) return;
          if (!one.ok || !one.body) {
            setRefused(true);
            return;
          }
          setWorkflow(one.body);
          if (trail.ok) {
            setAudit(trail.body ?? []);
          } else {
            setAudit([]);
            say("The history of this document could not be loaded.", "bad");
          }
        },
      ),
    [say],
  );

  useEffect(() => {
    if (!me || !id) return;
    load(id);
  }, [me, id, load]);

  useEffect(() => {
    document.title = workflow ? `${workflow.documentName} — SignFlow` : "SignFlow";
  }, [workflow]);

  /** Do it, say what the server said, then re-read the whole screen. */
  function run(
    action: () => Promise<{ ok: boolean; message: string | null; status: number }>,
    done: string,
    failed: string,
  ) {
    if (!id) return;
    setBusy(true);
    setConfirming(false);
    action().then((result) => {
      if (!mounted.current) return;
      say(
        result.ok
          ? done
          : result.status === 429
            ? "Too many attempts — please wait a few minutes and try again."
            : (result.message ?? failed),
        result.ok ? "good" : "bad",
      );
      // A refusal usually means the state moved while the page sat open, and
      // then the fresh answer is the only honest thing to show.
      load(id).finally(() => {
        if (mounted.current) setBusy(false);
      });
    });
  }

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  if (id === null || refused) {
    return (
      <AppShell me={me} narrow>
        <PageHeader
          title={id === null ? "That link is incomplete" : "That document is not here"}
          description={
            id === null
              ? "It does not say which document to open."
              : "There is no document at that address, or it is not one of yours."
          }
        />
        <SectionCard>
          <Button asChild size="lg" className="w-full">
            <Link href="/documents">My documents</Link>
          </Button>
        </SectionCard>
      </AppShell>
    );
  }

  if (!workflow) {
    return (
      <AppShell me={me}>
        <div className="space-y-4">
          <Skeleton className="h-9 w-72" />
          <Skeleton className="h-6 w-40" />
          <Skeleton className="h-48 w-full rounded-xl" />
        </div>
      </AppShell>
    );
  }

  const rows = audit ? buildTimeline(workflow, audit, me.username) : [];
  const story = rows.filter((row) => row.story);
  const recorded = rows.filter((row) => row.recorded);
  const finished = workflow.status === "COMPLETED";
  const yourTurn = workflow.currentSignerUserId === me.username;
  const live = !FINISHED.includes(workflow.status);

  return (
    <AppShell me={me}>
      <p className="mb-4 text-sm">
        <TextLink href="/documents">← My documents</TextLink>
      </p>

      <PageHeader
        title={workflow.documentName}
        action={
          finished ? (
            // A plain anchor with `download`: the browser fetches it with the
            // session cookie and the bytes never pass through this code.
            <Button asChild size="lg" variant="outline">
              <a href={api.downloadUrl(workflow.workflowId)} download>
                <Download aria-hidden="true" />
                Download the signed PDF
              </a>
            </Button>
          ) : yourTurn ? (
            <Button asChild size="lg">
              <Link href={`/open?id=${encodeURIComponent(workflow.workflowId)}`}>
                <PenLine aria-hidden="true" />
                Sign now
              </Link>
            </Button>
          ) : undefined
        }
      >
        <div className="flex flex-wrap items-center gap-3 pt-1">
          <StatusBadge status={workflow.status} />
        </div>
      </PageHeader>

      <div className="space-y-6">
        {yourTurn ? (
          <Card className="border-primary/30 bg-accent shadow-sm">
            <CardContent className="py-2">
              <p className="text-base">
                <span className="font-medium">Ready for your signature.</span>{" "}
                Signing happens in the SignFlow app on this computer, with your
                own card — this website never sees it.
              </p>
            </CardContent>
          </Card>
        ) : null}

        <NoteAlert note={note} />

        {/* The story first, the whole trail only if asked for. */}
        <SectionCard title="What has happened" icon={History}>
          {audit === null ? (
            <Skeleton className="h-24 w-full" />
          ) : rows.length === 0 ? (
            <p className="text-base text-muted-foreground">
              Nothing has been recorded yet.
            </p>
          ) : (
            <ol className="list-none">
              {story.map((row) => (
                <HistoryRow key={row.key} row={row} />
              ))}
            </ol>
          )}

          {recorded.some((row) => !row.story) ? (
            <>
              <Separator />
              <Button
                type="button"
                variant="ghost"
                onClick={() => setShowHistory(!showHistory)}
                aria-expanded={showHistory}
              >
                <ChevronDown
                  aria-hidden="true"
                  className={cn(
                    "transition-transform duration-200",
                    showHistory && "rotate-180",
                  )}
                />
                {showHistory ? "Hide history" : `Show history (${recorded.length})`}
              </Button>
              {showHistory ? (
                <ol className="list-none">
                  {recorded.map((row) => (
                    <HistoryRow key={row.key} row={row} />
                  ))}
                </ol>
              ) : null}
            </>
          ) : null}
        </SectionCard>

        {/* Cancelling cannot be undone, so it asks first and sits at the
            bottom, away from everything useful. The server allows it to the
            owner and only while the document is unsigned — the same two
            conditions here, so a button is never shown that the server would
            refuse. */}
        {live ? (
          <div className="border-t border-border pt-5">
            <Button
              type="button"
              variant="ghost"
              className="text-destructive hover:bg-destructive/10 hover:text-destructive"
              disabled={busy}
              onClick={() => setConfirming(true)}
            >
              <CircleSlash aria-hidden="true" />
              Cancel this document
            </Button>
          </div>
        ) : null}
      </div>

      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title="Cancel this document?"
        description="It cannot be signed after this, and it cannot be undone."
        confirmLabel="Yes, cancel it"
        busy={busy}
        onConfirm={() =>
          run(
            () => api.cancel(workflow.workflowId),
            "The document is cancelled.",
            "It could not be cancelled.",
          )
        }
      />

      {busy ? (
        <p className="mt-4 flex items-center gap-2 text-sm text-muted-foreground" role="status">
          <Busy>Working…</Busy>
        </p>
      ) : null}
    </AppShell>
  );
}
