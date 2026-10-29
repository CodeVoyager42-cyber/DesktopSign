"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Download, FileText, PenLine, Send } from "lucide-react";
import { EmptyState, PageHeader } from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import { DownloadRelease } from "@/components/download-release";
import {
  NoteAlert,
  SessionPending,
  useNote,
  useSession,
} from "@/components/session";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { api } from "@/lib/api";
import { FINISHED, groupRows, mergeRows, rowStory, type Row } from "@/lib/dashboard";

/**
 * The dashboard: what is waiting for you, and what you sent.
 *
 * Which group a document belongs in comes from the server's view of the
 * caller's own slot, never from the document's state alone — which is why
 * Phase E has two list endpoints and this screen reads both. The merging
 * itself is in `lib/dashboard.ts` and is unchanged.
 *
 * Three tabs, not the two the brief asked for, for one reason: a document you
 * are a signer of but did not send, and have already signed, is in neither
 * "To sign" nor "Sent by me". With two tabs it would vanish from the website
 * while other people were still signing it. The third appears only when there
 * is something in it.
 *
 * <b>Cards on a phone, a table on a desktop</b> — the same rows either way.
 * A table at 375 px is a horizontal scroll with four columns nobody can
 * read, and cards on a 1200 px screen waste the width that makes a list
 * scannable.
 */
function Actions({ row }: { row: Row }) {
  const finished = FINISHED.includes(row.status);
  return (
    <div className="flex flex-wrap items-center gap-2">
      {row.waitingForMe && !finished ? (
        <Button asChild>
          <Link href={`/open?id=${encodeURIComponent(row.id)}`}>
            <PenLine aria-hidden="true" />
            Sign now
          </Link>
        </Button>
      ) : null}
      {row.status === "COMPLETED" ? (
        // A plain anchor with `download`, not next/link: the browser fetches
        // it with the session cookie and the bytes never pass through this
        // code, which a client-side route would prevent.
        <Button asChild variant="outline">
          <a href={api.downloadUrl(row.id)} download>
            <Download aria-hidden="true" />
            Download
          </a>
        </Button>
      ) : null}
      <Button
        asChild
        variant={row.waitingForMe && !finished ? "ghost" : "outline"}
      >
        <Link href={`/document?id=${encodeURIComponent(row.id)}`}>View</Link>
      </Button>
    </div>
  );
}

function RowCards({ rows }: { rows: Row[] }) {
  return (
    <div className="space-y-3 sm:hidden">
      {rows.map((row) => (
        <Card key={row.id} className="shadow-sm">
          <CardContent className="space-y-3 py-2">
            <p className="flex items-start gap-2 font-medium">
              <FileText
                aria-hidden="true"
                className="mt-0.5 size-5 shrink-0 text-muted-foreground"
              />
              <span className="break-words">{row.name}</span>
            </p>
            <p className="text-sm text-muted-foreground">{rowStory(row)}</p>
            <StatusBadge status={row.status} />
            <Actions row={row} />
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

function RowTable({ rows }: { rows: Row[] }) {
  return (
    <Card className="hidden overflow-hidden shadow-sm sm:block">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Document</TableHead>
            <TableHead>Status</TableHead>
            <TableHead className="text-right">&nbsp;</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell className="py-4">
                <span className="flex items-start gap-2">
                  <FileText
                    aria-hidden="true"
                    className="mt-0.5 size-5 shrink-0 text-muted-foreground"
                  />
                  <span className="space-y-1">
                    <span className="block break-words font-medium">{row.name}</span>
                    <span className="block text-sm text-muted-foreground">
                      {rowStory(row)}
                    </span>
                  </span>
                </span>
              </TableCell>
              <TableCell className="py-4 align-top">
                <StatusBadge status={row.status} />
              </TableCell>
              <TableCell className="py-4 text-right align-top">
                <span className="inline-flex justify-end">
                  <Actions row={row} />
                </span>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Card>
  );
}

function Group({ rows, empty }: { rows: Row[]; empty: React.ReactNode }) {
  if (rows.length === 0) {
    return <>{empty}</>;
  }
  return (
    <>
      <RowCards rows={rows} />
      <RowTable rows={rows} />
    </>
  );
}

export default function Documents() {
  const { me, state } = useSession();
  const { note, say } = useNote();
  const [rows, setRows] = useState<Row[] | null>(null);

  useEffect(() => {
    if (!me) return;
    let live = true;
    Promise.all([api.owned(), api.participating()]).then(([owned, participating]) => {
      if (!live) return;
      if (!owned.ok || !participating.ok) {
        say("Your documents could not be loaded. Try again in a moment.", "bad");
        setRows([]);
        return;
      }
      setRows(mergeRows(owned.body ?? [], participating.body?.tasks ?? [], me.username));
    });
    return () => {
      live = false;
    };
  }, [me, say]);

  const all = rows ?? [];
  const { mine } = groupRows(all);
  const sent = all.filter((row) => row.sent);
  const shared = all.filter((row) => !row.sent && !mine.includes(row));

  useEffect(() => {
    document.title = mine.length ? `SignFlow — ${mine.length} to sign` : "SignFlow";
  }, [mine.length]);

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  return (
    <AppShell me={me}>
      <PageHeader
        title="Documents"
        description="What is waiting for you, and what you have sent out."
        action={
          <Button asChild size="lg">
            <Link href="/send">
              <Send aria-hidden="true" />
              Send a document
            </Link>
          </Button>
        }
      />

      {rows === null ? (
        <div className="space-y-3">
          <Skeleton className="h-11 w-64" />
          <Skeleton className="h-28 w-full rounded-xl" />
          <Skeleton className="h-28 w-full rounded-xl" />
        </div>
      ) : (
        <Tabs defaultValue={mine.length ? "to-sign" : "sent"}>
          <TabsList className="w-full justify-start overflow-x-auto">
            <TabsTrigger value="to-sign" className="gap-2">
              To sign
              {mine.length ? (
                <span className="rounded-full bg-primary px-2 py-0.5 text-xs font-semibold text-primary-foreground">
                  {mine.length}
                </span>
              ) : null}
            </TabsTrigger>
            <TabsTrigger value="sent">Sent by me</TabsTrigger>
            {shared.length ? (
              <TabsTrigger value="shared">Shared with me</TabsTrigger>
            ) : null}
          </TabsList>

          <TabsContent value="to-sign" className="mt-5 space-y-4">
            <Group
              rows={mine}
              empty={
                <EmptyState
                  icon={PenLine}
                  title="Nothing to sign"
                  description="When somebody asks you to sign a document, it appears here."
                />
              }
            />
          </TabsContent>

          <TabsContent value="sent" className="mt-5">
            <Group
              rows={sent}
              empty={
                <EmptyState
                  icon={FileText}
                  title="No documents yet"
                  description="Send a PDF and we will ask the signers for you."
                  action={
                    <Button asChild size="lg">
                      <Link href="/send">
                        <Send aria-hidden="true" />
                        Send your first document
                      </Link>
                    </Button>
                  }
                />
              }
            />
          </TabsContent>

          {shared.length ? (
            <TabsContent value="shared" className="mt-5">
              <Group rows={shared} empty={null} />
            </TabsContent>
          ) : null}
        </Tabs>
      )}

      {/* The download section, for every reader: somebody who has something
          to sign needs the app now, and somebody who does not may still want
          it installed before that moment comes. */}
      <div className="mt-6">
        <DownloadRelease />
      </div>

      <div className="mt-6">
        <NoteAlert note={note} />
      </div>
    </AppShell>
  );
}
