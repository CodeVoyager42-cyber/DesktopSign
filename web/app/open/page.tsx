"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { AppHeader, SiteFooter } from "../../components/SiteChrome";
import { NoteRegion, SessionPending, useNote, useSession } from "../../components/ui";
import { api, type AppInfo, type Workflow } from "../../lib/api";
import {
  clock,
  refusalWords,
  secondsLeft,
  worthRetrying,
  type Phase,
} from "../../lib/handoff";
import styles from "./open.module.css";

/**
 * The hand-off: the screen that opens SignFlow on this computer, at this one
 * signing task (`docs/frontend-design.md` §6).
 *
 * <b>The browser never becomes the signing environment.</b> The certificate
 * stays on the smart card and the desktop application does the signing. This
 * screen's whole job is to say that clearly enough that nobody looks for an
 * upload button — which is why it names the document, says what is about to
 * happen, and offers nothing that looks like signing.
 *
 * <b>The code is never shown.</b> The server answers with a deep link
 * carrying a single-use code, and the page navigates to it rather than
 * rendering it as a link: an `<a href>` would put a credential in the DOM for
 * anyone to copy, and the one thing Phase B asks of this screen is that the
 * code goes to the application and nowhere else. It is also why there is a
 * clock — the code is valid for two minutes, and after that only a fresh one
 * will do, never a replay of the old one.
 *
 * <b>Why a button as well as the automatic attempt.</b> Navigating to a
 * custom scheme is a privileged thing to do: browsers may ignore it when it
 * was not caused by a click, and some ask the reader to confirm. So the page
 * tries once on arrival (the reader did click something to get here) and
 * leaves a primary action that tries again, rather than sitting on "Opening…"
 * forever if the automatic attempt was refused.
 */
export default function Sign() {
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

  /** Asks for a code and hands it to the application. */
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
        // The navigation that starts the application. Assigning location is
        // deliberate: it carries the code without ever putting it in the
        // page, and a failed scheme navigation leaves the page as it is.
        window.location.href = result.body.deepLink;
      } catch {
        say(
          "This browser would not open SignFlow by itself. Use the button below.",
          "info",
        );
      }
    });
  }, [say]);

  useEffect(() => {
    if (!me || !id) return;
    api.workflow(id).then((result) => {
      if (!mounted.current) return;
      if (result.ok && result.body) {
        setWorkflow(result.body);
      }
    });
    api.appInfo().then((result) => {
      if (mounted.current && result.ok && result.body) setApp(result.body);
    });
    open(id);
  }, [me, id, open]);

  // One tick a second while a code is live, and the clock is read from the
  // issue time rather than counted down in state: a tab that was asleep for
  // a minute comes back with the right answer instead of a stale one.
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
      <>
        <AppHeader me={me} />
        <main id="main" className="panel">
          <h1>That link is incomplete</h1>
          <p className="lede">It does not say which document to sign.</p>
          <p className="links">
            <Link href="/documents">Your documents</Link>
          </p>
        </main>
        <SiteFooter />
      </>
    );
  }

  const name = workflow?.documentName;
  const appName = app?.appName ?? "SignFlow";

  return (
    <>
      <AppHeader me={me} />
      <main id="main" className="panel">
        {phase === "asking" ? <h1>Opening {appName}…</h1> : null}
        {phase === "opening" ? <h1>Opening {appName}…</h1> : null}
        {phase === "expired" ? <h1>The link timed out</h1> : null}
        {phase === "refused" ? <h1>This cannot be opened for signing</h1> : null}

        {name ? (
          <p className="lede">
            {phase === "refused" ? name : <>You are signing <strong>{name}</strong>.</>}
          </p>
        ) : null}

        {phase === "opening" ? (
          <>
            <p className={styles.clock} role="status" aria-live="polite">
              <span className={styles.remaining}>{clock(left)}</span> left to use
              this link
            </p>
            <p className="hint">
              {appName} should be asking for your card. Signing happens there,
              on this computer — the certificate never leaves your card, and
              this website never sees it.
            </p>
            <button type="button" className="button block" onClick={() => open(id!)}>
              Open {appName} again
            </button>
          </>
        ) : null}

        {phase === "expired" ? (
          <>
            <p className="lede">
              A signing link is good for two minutes, so this one is no longer
              usable. The next one is new — never the same link again.
            </p>
            <button type="button" className="button block" onClick={() => open(id!)}>
              Get a new link
            </button>
          </>
        ) : null}

        {phase === "refused" ? (
          <>
            <p className="note bad" role="status">
              {refusalWords(refusal.message)}
            </p>
            {worthRetrying(refusal.code) ? (
              <button type="button" className="button block" onClick={() => open(id!)}>
                Try again
              </button>
            ) : null}
          </>
        ) : null}

        {/* The fallback, available throughout: the commonest reason nothing
            happens is that the application is not installed on this
            computer. A link only when the deployment has one to give —
            inventing a download URL would be worse than saying nothing. */}
        <p className={styles.fallback}>
          Nothing happened?{" "}
          {app?.downloadUrl ? (
            <a href={app.downloadUrl}>Install {appName}</a>
          ) : (
            <span className="muted">
              {appName} has to be installed on this computer to sign.
            </span>
          )}
        </p>

        <NoteRegion note={note} />
        <p className="links">
          <Link href={`/document?id=${encodeURIComponent(id ?? "")}`}>
            This document
          </Link>
          <span className="sep" aria-hidden="true">
            ·
          </span>
          <Link href="/documents">Documents</Link>
        </p>
      </main>
      <SiteFooter />
    </>
  );
}
