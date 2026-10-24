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
import { api, type AuditEntry, type Workflow } from "../../lib/api";
import { STATE_WORDS } from "../../lib/dashboard";
import { buildTimeline, type TimelineRow } from "../../lib/timeline";
import styles from "./document.module.css";

/**
 * One document: where it stands, and what happened to it in order
 * (docs/frontend-design.md §6, "Document status timeline").
 *
 * <b>Why the id is a query parameter</b> and not `/documents/<id>`. The
 * website is a static export with no server of its own, so a path segment
 * that varies per document would need either a page built per workflow — they
 * are created at runtime — or a catch-all forward in
 * `FrontendRoutingConfiguration`, which is exactly the trap that class
 * refuses: a `/**` forward answers every mistyped path with an HTML 200 and
 * turns a clean 404 into something no client can tell from success. So the
 * document is named in the query string, as the tokens in the e-mail links
 * are, and `/document` is one more line in `PAGES`.
 *
 * <b>Two reads, no third.</b> The screen asks for the workflow and its audit
 * trail. It deliberately does not also fetch the lists to learn who sent the
 * document: that is a third request for one line of prose, and the trail
 * already names who created it.
 *
 * Refusals are the server's to make. Anyone not in a workflow gets the same
 * 404 as for a workflow that was never created, so this page says the same
 * thing for both — wording that distinguished them would undo the uniform
 * answer the server is careful to give.
 */

function Row({ row }: { row: TimelineRow }) {
  return (
    <li className={`${styles.row} ${styles[row.mark]}`}>
      {/* The dot is reinforcement, never the only carrier: the words of a
          blocked or pending row say so themselves (§4). */}
      <span className={styles.dot} aria-hidden="true" />
      <p className={styles.words}>{row.words}</p>
      {row.who || row.when ? (
        <p className={`muted ${styles.meta}`}>
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
  // read yet" and `null` is "there is no id in it". Collapsing them would
  // show "that link is incomplete" for one paint on a perfectly good link.
  const [id, setId] = useState<string | null | undefined>(undefined);
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [audit, setAudit] = useState<AuditEntry[] | null>(null);
  const [refused, setRefused] = useState(false);

  useEffect(() => {
    setId(new URLSearchParams(window.location.search).get("id"));
  }, []);

  useEffect(() => {
    if (!me || !id) return;
    let live = true;
    Promise.all([api.workflow(id), api.audit(id)]).then(([one, trail]) => {
      if (!live) return;
      if (!one.ok || !one.body) {
        setRefused(true);
        return;
      }
      setWorkflow(one.body);
      if (trail.ok) {
        setAudit(trail.body ?? []);
      } else {
        // The document is readable but its history is not — the header can
        // still be shown, and saying so beats an empty timeline that reads
        // as "nothing happened".
        setAudit([]);
        say("The history of this document could not be loaded.", "bad");
      }
    });
    return () => {
      live = false;
    };
  }, [me, id, say]);

  useEffect(() => {
    document.title = workflow ? `${workflow.documentName} — SignFlow` : "SignFlow";
  }, [workflow]);

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  if (id === null) {
    return (
      <>
        <AppHeader me={me} />
        <main id="main" className="panel">
          <h1>That link is incomplete</h1>
          <p className="lede">It does not say which document to open.</p>
          <p className="links">
            <Link href="/documents">Your documents</Link>
          </p>
        </main>
        <SiteFooter />
      </>
    );
  }

  if (refused) {
    return (
      <>
        <AppHeader me={me} />
        <main id="main" className="panel">
          <h1>That document is not here</h1>
          <p className="lede">
            There is no document at that address, or it is not one of yours.
          </p>
          <p className="links">
            <Link href="/documents">Your documents</Link>
          </p>
        </main>
        <SiteFooter />
      </>
    );
  }

  const rows = workflow && audit ? buildTimeline(workflow, audit, me.username) : [];
  const story = rows.filter((row) => row.story);
  // The pending row is not one of them: it is read from the current state,
  // and a disclosure that counted it would be claiming the server recorded
  // something that has not happened.
  const recorded = rows.filter((row) => row.recorded);
  const finished = workflow?.status === "COMPLETED";
  const yourTurn = workflow?.currentSignerUserId === me.username;

  return (
    <>
      <AppHeader me={me} />
      <main id="main" className="column">
        {!workflow ? (
          <p className="lede">Loading…</p>
        ) : (
          <>
            <h1>{workflow.documentName}</h1>
            <p className="lede">
              <Badge pending={!finished}>
                {STATE_WORDS[workflow.status] ?? workflow.status}
              </Badge>{" "}
              {workflow.completedSignatures} of {workflow.totalSignatures} signed
              {workflow.signingPolicy === "FLEXIBLE" ? " · any order" : ""}
            </p>

            {yourTurn ? (
              // Signing needs the certificate on the signer's own card, so
              // there is no button here that could do it; the hand-off that
              // opens the application is Phase F.
              //
              // Only when the server names this account as the current
              // signer. An unclaimed FLEXIBLE turn is not enough: the
              // workflow says a turn is free, not that this reader may take
              // it, and the owner of a workflow they do not sign would be
              // told to go and sign it. The pending row already says what
              // is being waited for.
              <p className="notice">Open SignFlow on your computer to sign this.</p>
            ) : null}

            {finished ? (
              <p className="more">
                <a href={api.downloadUrl(workflow.workflowId)} download>
                  Download the signed PDF <span aria-hidden="true">›</span>
                </a>
              </p>
            ) : null}

            <h2>History</h2>
            {audit === null ? <p className="muted">Loading…</p> : null}
            {audit !== null && rows.length === 0 ? (
              <p className="muted">Nothing has been recorded yet.</p>
            ) : null}
            {story.length > 0 ? (
              <ol className={styles.rail}>
                {story.map((row) => (
                  <Row key={row.key} row={row} />
                ))}
              </ol>
            ) : null}

            {/* The full trail is a disclosure rather than a second screen:
                the story above is written from the same rows, and somebody
                who wants the bookkeeping wants it in place. */}
            {/* Shown when some recorded row is not in the story — comparing
                the two lengths would miss it, since the story also carries
                the pending row, which no recorded list has. */}
            {recorded.some((row) => !row.story) ? (
              <details className={styles.all}>
                <summary>Everything that was recorded ({recorded.length})</summary>
                <ol className={styles.rail}>
                  {recorded.map((row) => (
                    <Row key={row.key} row={row} />
                  ))}
                </ol>
              </details>
            ) : null}
          </>
        )}

        <NoteRegion note={note} />
        <p className="links">
          <Link href="/documents">Documents</Link>
        </p>
      </main>
      <SiteFooter />
    </>
  );
}
