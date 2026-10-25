"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AppHeader, SiteFooter } from "../../components/SiteChrome";
import {
  Badge,
  NoteRegion,
  Separator,
  SessionPending,
  useNote,
  useSession,
} from "../../components/ui";
import { api } from "../../lib/api";
import {
  FINISHED,
  STATE_WORDS,
  groupRows,
  mergeRows,
  rowStory,
  type Row,
} from "../../lib/dashboard";
import styles from "./documents.module.css";

/**
 * The dashboard: three groups, each with its count in the heading, and the
 * action-required count in the tab title so a request that arrived while this
 * tab sat open is visible without reading the page.
 *
 * Which group a row belongs in comes from the server's view of the caller's
 * own slot, never from the document's state alone — which is why Phase E has
 * two list endpoints and this screen reads both. The merging itself is in
 * `lib/dashboard.ts`.
 */
function Group({
  heading,
  rows,
  emptyWords,
  note,
}: {
  heading: string;
  rows: Row[];
  emptyWords: string;
  note?: (row: Row) => React.ReactNode;
}) {
  return (
    <section>
      <h2 className={styles.groupHeading}>
        <span>{heading}</span>
        {rows.length ? <span className={styles.count}>{rows.length}</span> : null}
      </h2>
      {/* An empty group is one muted line, not an empty box: a dashboard
          whose first impression is three empty panels reads as broken. */}
      {rows.length === 0 ? (
        <p className={`muted ${styles.empty}`}>{emptyWords}</p>
      ) : (
        rows.map((row) => (
          <article key={row.id} className="card">
            <h3>{row.name}</h3>
            <p>{rowStory(row)}</p>
            {FINISHED.includes(row.status) ? (
              <Badge pending={row.status !== "COMPLETED"}>
                {STATE_WORDS[row.status] ?? row.status}
              </Badge>
            ) : (
              <p>{STATE_WORDS[row.status] ?? row.status}</p>
            )}
            {/* Every row opens the document's own screen — where it stands
                and what happened to it, in order. The plan gave the rows
                this link from the start; it waited for the screen to exist
                rather than pointing at nothing. */}
            <p className="more">
              <Link href={`/document?id=${encodeURIComponent(row.id)}`}>
                Open <span aria-hidden="true">›</span>
              </Link>
              {row.status === "COMPLETED" ? (
                <>
                  <Separator />
                  {/* A plain link with `download`, not a fetch: the browser
                      gets it with the session cookie and the bytes never
                      pass through this code. */}
                  <a href={api.downloadUrl(row.id)} download>
                    Download the signed PDF <span aria-hidden="true">›</span>
                  </a>
                </>
              ) : null}
            </p>
            {note ? note(row) : null}
          </article>
        ))
      )}
    </section>
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

  const { mine, theirs, finished } = groupRows(rows ?? []);

  useEffect(() => {
    document.title = mine.length ? `SignFlow — ${mine.length} to sign` : "SignFlow";
  }, [mine.length]);

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  return (
    <>
      <AppHeader me={me} />
      <main id="main" className="column">
        <h1>Documents</h1>
        {/* The one thing this screen is for other than reading: sending
            something out. A primary action beside the heading, because a
            dashboard whose only verbs are other people's reads as a
            waiting room. */}
        <p className={styles.send}>
          <Link href="/send" className="button">
            Send a document
          </Link>
        </p>
        {rows === null ? <p className="lede">Loading…</p> : null}
        {rows !== null && rows.length === 0 && !note ? (
          <p className="lede">
            Nothing here yet. A document appears once you are asked to sign
            one, or you send one out.
          </p>
        ) : null}

        {rows !== null && rows.length > 0 ? (
          <>
            <Group
              heading="Action required"
              rows={mine}
              emptyWords="Nothing is waiting for your signature."
              // The hand-off (Phase F). Signing still happens on the signer's
              // own computer with their own card — this link does not sign
              // anything, it opens the application at this one task and says
              // so on the way.
              note={(row) => (
                <p className={styles.handoff}>
                  <Link
                    href={`/open?id=${encodeURIComponent(row.id)}`}
                    className="button"
                  >
                    Sign it in SignFlow
                  </Link>
                </p>
              )}
            />
            <Group
              heading="Waiting for others"
              rows={theirs}
              emptyWords="Nothing of yours is in flight."
            />
            <Group
              heading="Finished"
              rows={finished}
              emptyWords="Nothing finished yet."
            />
          </>
        ) : null}

        <NoteRegion note={note} />
        <p className="links">
          <Link href="/account">Your account</Link>
        </p>
      </main>
      <SiteFooter />
    </>
  );
}
