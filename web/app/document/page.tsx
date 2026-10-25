"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { AppHeader, SiteFooter } from "../../components/SiteChrome";
import {
  Badge,
  NoteRegion,
  SessionPending,
  useNote,
  useSession,
} from "../../components/ui";
import {
  api,
  type AuditEntry,
  type InvitationStatus,
  type Workflow,
} from "../../lib/api";
import { FINISHED, STATE_WORDS } from "../../lib/dashboard";
import {
  canResend,
  canWithdraw,
  slotNeedsAttention,
  slotStory,
} from "../../lib/send";
import { at, buildTimeline, type TimelineRow } from "../../lib/timeline";
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
  const [slots, setSlots] = useState<InvitationStatus[] | null>(null);
  const [refused, setRefused] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    setId(new URLSearchParams(window.location.search).get("id"));
  }, []);

  // Actions re-read the screen afterwards, so the load is a callback rather
  // than only an effect. One mounted flag for all of them: a reply that
  // arrives after the reader has left should not be written anywhere.
  const mounted = useRef(true);
  useEffect(() => () => {
    mounted.current = false;
  }, []);

  const load = useCallback(
    (workflowId: string) =>
      Promise.all([
        api.workflow(workflowId),
        api.audit(workflowId),
        api.invitations(workflowId),
      ]).then(([one, trail, invited]) => {
        if (!mounted.current) return;
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
        if (invited.ok) {
          setSlots(invited.body ?? []);
        } else if (invited.code === "WORKFLOW_NOT_ACCESSIBLE") {
          // Not a failure: managing invitations is the owner's, and a signer
          // reading their own document is told so by this exact code. The
          // panel is simply not theirs, and nothing is said about it.
          setSlots(null);
        } else {
          setSlots(null);
          say("Who was invited could not be loaded.", "bad");
        }
      }),
    [say],
  );

  useEffect(() => {
    if (!me || !id) return;
    load(id);
  }, [me, id, load]);

  useEffect(() => {
    document.title = workflow ? `${workflow.documentName} — SignFlow` : "SignFlow";
  }, [workflow]);

  /**
   * One path for every action: do it, say what the server said, then re-read
   * the whole screen.
   *
   * The re-read is not a courtesy. A refusal here usually means the state
   * moved — the slot signed while the page sat open, the workflow completed —
   * and in that case the fresh answer is the only honest thing to show. The
   * timeline picks up the new audit event by the same read.
   */
  function run(
    action: () => Promise<{ ok: boolean; message: string | null }>,
    done: string,
    failed: string,
  ) {
    if (!id) return;
    setBusy(true);
    setConfirming(false);
    action().then((result) => {
      if (!mounted.current) return;
      say(result.ok ? done : (result.message ?? failed), result.ok ? "good" : "bad");
      load(id).finally(() => {
        if (mounted.current) setBusy(false);
      });
    });
  }

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
  // A successful read of the invitations is what tells this page the viewer
  // created the workflow: the server grants that read to the owner alone, so
  // it is the server's answer and not a guess from the data.
  const owner = slots !== null;
  const live = workflow !== null && !FINISHED.includes(workflow.status);

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
              <div className="notice">
                <p>
                  It is your turn. Signing happens in SignFlow on this
                  computer, with your own card — this website never sees it.
                </p>
                <p className={styles.handoff}>
                  <Link
                    href={`/open?id=${encodeURIComponent(workflow.workflowId)}`}
                    className="button"
                  >
                    Sign it in SignFlow
                  </Link>
                </p>
              </div>
            ) : null}

            {finished ? (
              <p className={styles.download}>
                <a href={api.downloadUrl(workflow.workflowId)} download>
                  Download the signed PDF <span aria-hidden="true">›</span>
                </a>
              </p>
            ) : null}

            {/* The owner's panel. A signer sees none of it: the server
                refuses them this read, which is what `owner` is. */}
            {owner && slots ? (
              <section>
                <h2>Who was invited</h2>
                {slots.length === 0 ? (
                  <p className="muted">
                    Nobody was invited by e-mail: every slot was assigned to an
                    account that already existed.
                  </p>
                ) : null}
                <ul className={styles.slots}>
                  {slots.map((slot) => {
                    // Both are read through `at`, which answers null for a
                    // timestamp it cannot parse — so they are computed here
                    // rather than interpolated, where a null would be shown
                    // to the reader as the word "null".
                    const expires =
                      slot.status === "PENDING" && !slot.slotSigned && slot.expiresAt
                        ? at(slot.expiresAt)
                        : null;
                    const opened = slot.acceptedAt ? at(slot.acceptedAt) : null;
                    return (
                    <li key={slot.signerOrder} className="card">
                      <h3>
                        Signer {slot.signerOrder}
                        {slot.email ? ` · ${slot.email}` : ""}
                      </h3>
                      <p>
                        {slotNeedsAttention(slot) ? (
                          <Badge pending>{slotStory(slot)}</Badge>
                        ) : (
                          slotStory(slot)
                        )}
                        {expires ? ` · the link expires ${expires}` : ""}
                        {opened ? ` · opened ${opened}` : ""}
                      </p>
                      {canResend(slot) || canWithdraw(slot) ? (
                        <p className={styles.actions}>
                          {canResend(slot) && slot.invitationId !== null ? (
                            <button
                              type="button"
                              className="text-button"
                              disabled={busy}
                              onClick={() =>
                                run(
                                  () => api.resendInvitation(slot.invitationId as number),
                                  "A new invitation is on its way. The previous link no longer works.",
                                  "The invitation could not be sent again.",
                                )
                              }
                            >
                              Send it again
                            </button>
                          ) : null}
                          {canWithdraw(slot) && slot.invitationId !== null ? (
                            <button
                              type="button"
                              className="text-button"
                              disabled={busy}
                              onClick={() =>
                                run(
                                  () => api.revokeInvitation(slot.invitationId as number),
                                  "The invitation is withdrawn. Its link no longer works.",
                                  "The invitation could not be withdrawn.",
                                )
                              }
                            >
                              Withdraw it
                            </button>
                          ) : null}
                        </p>
                      ) : null}
                    </li>
                    );
                  })}
                </ul>
                {slots.length > 0 ? (
                  <p className="hint">
                    Sending an invitation again replaces the previous link,
                    which stops working at once. Withdrawing one leaves the
                    slot unsigned and nobody invited to it.
                  </p>
                ) : null}
              </section>
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

        {/* Cancelling ends the request for everyone and cannot be undone, so
            it asks first and sits at the bottom, away from everything that is
            merely useful. The server allows it to the creator or to whoever
            is being waited for, and only while it is unfinished — the same
            two conditions tested here, so a button is never shown that the
            server would refuse. */}
        {workflow && live && (owner || yourTurn) ? (
          <section className={styles.stop}>
            {confirming ? (
              <>
                <p>
                  Cancel this signing request? Signatures already given stay in
                  the record, nobody can sign it after this, and it cannot be
                  undone.
                </p>
                <p className={styles.actions}>
                  <button
                    type="button"
                    className="button"
                    disabled={busy}
                    onClick={() =>
                      run(
                        () => api.cancel(workflow.workflowId),
                        "The signing request is cancelled.",
                        "It could not be cancelled.",
                      )
                    }
                  >
                    Yes, cancel it
                  </button>
                  <button
                    type="button"
                    className="text-button"
                    onClick={() => setConfirming(false)}
                  >
                    Keep it
                  </button>
                </p>
              </>
            ) : (
              <button
                type="button"
                className="text-button"
                onClick={() => setConfirming(true)}
              >
                Cancel this signing request
              </button>
            )}
          </section>
        ) : null}

        <NoteRegion note={note} />
        <p className="links">
          <Link href="/documents">Documents</Link>
        </p>
      </main>
      <SiteFooter />
    </>
  );
}
