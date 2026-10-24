"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AppHeader, SiteFooter } from "../../components/SiteChrome";
import {
  Badge,
  NoteRegion,
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
  action,
}: {
  heading: string;
  rows: Row[];
  emptyWords: string;
  action?: (row: Row) => React.ReactNode;
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
            {action ? action(row) : null}
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
              // Signing happens on the signer's own computer, with their own
              // card: there is no button here that could do it. Saying so
              // beats a link that cannot work — the hand-off that opens the
              // application is Phase F.
              action={() => (
                <p className={`muted ${styles.handoff}`}>
                  Open SignFlow on your computer to sign this.
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
              action={(row) =>
                row.status === "COMPLETED" ? (
                  // A plain link with `download`, not a fetch: the browser
                  // gets it with the session cookie and the bytes never pass
                  // through this code.
                  <p className="more">
                    <a href={api.downloadUrl(row.id)} download>
                      Download the signed PDF <span aria-hidden="true">›</span>
                    </a>
                  </p>
                ) : null
              }
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
