"use client";

import Link from "next/link";
import { useState } from "react";
import { AppHeader, SiteFooter } from "../../components/SiteChrome";
import { NoteRegion, SessionPending, useNote, useSession } from "../../components/ui";
import { api } from "../../lib/api";
import {
  POLICIES,
  documentNameFor,
  problemWith,
  signersFrom,
} from "../../lib/send";
import styles from "./send.module.css";

/**
 * Sending a document out for signature.
 *
 * <b>There is no confirmation screen.</b> On success this goes straight to
 * the document's own screen, where the server's audit trail already says what
 * happened — created, invited, the invitation went out — and says it from the
 * record rather than from this page's memory of what it sent. A success panel
 * here would be a second account of the same event, and the one that goes
 * stale.
 *
 * <b>The invitation links are not shown.</b> The create call answers with the
 * exact links that were e-mailed, once, and this page drops them. A link is
 * the credential that signs as that person: the e-mail is the channel it was
 * meant for, a browser on a shared desk is not, and when delivery fails the
 * answer is *Resend* on the document screen rather than a link to copy out of
 * here. The desktop shows them because it had no delivery tracking to offer
 * instead; the website does.
 *
 * <b>No certificates.</b> Every slot is created unbound: a certificate lives
 * on the signer's smart card, and each signer binds their own from their own
 * machine. So a workflow starts out saying it is waiting for certificates,
 * which is the truth and is what the document screen will show.
 */
export default function Send() {
  const { me, state } = useSession();
  const { note, say, clear } = useNote();
  const [file, setFile] = useState<File | null>(null);
  const [documentName, setDocumentName] = useState("");
  // Whether the reader has typed a name of their own. Choosing another file
  // should replace a defaulted name and never overwrite a deliberate one.
  const [named, setNamed] = useState(false);
  const [rows, setRows] = useState<string[]>([""]);
  const [policy, setPolicy] = useState<string>(POLICIES[0].value);
  const [busy, setBusy] = useState(false);

  function chooseFile(chosen: File | null) {
    setFile(chosen);
    clear();
    if (chosen && !named) {
      setDocumentName(documentNameFor(chosen.name));
    }
  }

  function setRow(index: number, value: string) {
    setRows(rows.map((row, at) => (at === index ? value : row)));
  }

  function send() {
    const problem = problemWith(file, documentName, rows);
    if (problem || !file) {
      say(problem ?? "Choose the PDF you want signed.", "bad");
      return;
    }
    setBusy(true);
    say("Sending…");
    api
      .createWorkflow(documentName.trim(), policy, signersFrom(rows), file)
      .then((result) => {
        if (!result.ok || !result.body) {
          // The server's own message: it knows which address is malformed,
          // which account does not exist and how large the file may be.
          say(result.message ?? "The document could not be sent.", "bad");
          setBusy(false);
          return;
        }
        window.location.assign(
          `/document?id=${encodeURIComponent(result.body.workflow.workflowId)}`,
        );
      });
  }

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  // The server refuses to create a workflow for an account that has not
  // confirmed its address (the Phase D gate, in the service). Saying so here
  // beats a form that fills in and then fails.
  if (!me.emailVerified) {
    return (
      <>
        <AppHeader me={me} />
        <main id="main" className="panel">
          <h1>Confirm your address first</h1>
          <p className="lede">
            Sending a document for signature needs a confirmed e-mail address.
            Your account screen can send the confirmation link again.
          </p>
          <p className="links">
            <Link href="/account">Your account</Link>
          </p>
        </main>
        <SiteFooter />
      </>
    );
  }

  return (
    <>
      <AppHeader me={me} />
      <main id="main" className="column">
        <h1>Send a document</h1>
        <p className="lede">
          Choose the PDF, say who signs it, and each signer is e-mailed a link.
          They sign in SignFlow on their own computer, with their own card.
        </p>

        <div className="sheet">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              send();
            }}
          >
            <p className="field">
              <label htmlFor="file">The document</label>
              <input
                id="file"
                name="file"
                type="file"
                accept="application/pdf,.pdf"
                onChange={(event) => chooseFile(event.target.files?.[0] ?? null)}
              />
            </p>
            <p className="hint">A PDF. Nothing else can be signed.</p>

            <p className="field">
              <label htmlFor="documentName">Its name</label>
              <input
                id="documentName"
                name="documentName"
                type="text"
                value={documentName}
                onChange={(event) => {
                  setNamed(true);
                  setDocumentName(event.target.value);
                }}
              />
            </p>
            <p className="hint">
              What everyone will see it called, and what it is downloaded as.
            </p>

            <fieldset className={styles.group}>
              <legend>Who signs it</legend>
              {rows.map((row, index) => (
                <p className={styles.signer} key={index}>
                  <label htmlFor={`signer-${index}`}>Signer {index + 1}</label>
                  <span className={styles.signerRow}>
                    <input
                      id={`signer-${index}`}
                      type="text"
                      value={row}
                      autoComplete="off"
                      placeholder="name@example.com"
                      onChange={(event) => setRow(index, event.target.value)}
                    />
                    {rows.length > 1 ? (
                      <button
                        type="button"
                        className="text-button"
                        onClick={() => setRows(rows.filter((_, at) => at !== index))}
                      >
                        Remove
                      </button>
                    ) : null}
                  </span>
                </p>
              ))}
              <p className="hint">
                An e-mail address is invited. A SignFlow username that already
                exists is assigned the slot directly.
              </p>
              <button
                type="button"
                className="button ghost"
                onClick={() => setRows([...rows, ""])}
              >
                Add another signer
              </button>
            </fieldset>

            <fieldset className={styles.group}>
              <legend>In what order</legend>
              {POLICIES.map((choice) => (
                <span className={styles.choice} key={choice.value}>
                  <input
                    id={`policy-${choice.value}`}
                    type="radio"
                    name="policy"
                    value={choice.value}
                    checked={policy === choice.value}
                    onChange={() => setPolicy(choice.value)}
                  />
                  <label htmlFor={`policy-${choice.value}`}>
                    {choice.label}
                    <span className={`muted ${styles.choiceHint}`}>{choice.hint}</span>
                  </label>
                </span>
              ))}
            </fieldset>

            <button type="submit" className="button block" disabled={busy}>
              Send for signature
            </button>
          </form>
        </div>

        <NoteRegion note={note} />
        <p className="links">
          <Link href="/documents">Documents</Link>
        </p>
      </main>
      <SiteFooter />
    </>
  );
}
