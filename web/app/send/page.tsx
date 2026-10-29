"use client";

import Link from "next/link";
import { useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  FileText,
  Plus,
  Send as SendIcon,
  Trash2,
  Upload,
} from "lucide-react";
import {
  Initials,
  Links,
  PageHeader,
  SectionCard,
  TextLink,
} from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import {
  Busy,
  Field,
  NoteAlert,
  SessionPending,
  useNote,
  useSession,
} from "@/components/session";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Separator } from "@/components/ui/separator";
import { api } from "@/lib/api";
import { POLICIES, documentNameFor, problemWith, signersFrom } from "@/lib/send";
import { cn } from "@/lib/utils";

/**
 * Sending a document out, in three steps.
 *
 * The one-screen form it replaces asked for everything at once: a file, a
 * name, any number of signer rows and an order. Three questions in sequence
 * are easier to answer than nine fields at once, and the step the reader is
 * on is the only thing on screen.
 *
 * Nothing about what is sent has changed: the same `problemWith`,
 * `signersFrom` and `documentNameFor` from `lib/send.ts`, the same multipart
 * call, and still no certificate named from the browser. On success it goes
 * to the document's own screen, where the server's audit trail is the
 * receipt — a success panel here would be a second account of the same
 * event, and the one that goes stale.
 *
 * <b>There is no name field per signer</b>, and that is the API rather than
 * an omission: a signer slot is an email address (which is invited) or a
 * username that already exists. A name box would be collected and thrown
 * away. The initials beside each row come from the address itself.
 */
const STEPS = ["Choose the PDF", "Who signs", "Review and send"];

export default function Send() {
  const { me, state } = useSession();
  const { note, say, clear } = useNote();
  const [step, setStep] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [documentName, setDocumentName] = useState("");
  // Whether the reader has typed a name of their own. Choosing another file
  // should replace a defaulted name and never overwrite a deliberate one.
  const [named, setNamed] = useState(false);
  const [rows, setRows] = useState<string[]>([""]);
  const [policy, setPolicy] = useState<string>(POLICIES[0].value);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);

  function chooseFile(chosen: File | null) {
    setFile(chosen);
    clear();
    if (chosen && !named) {
      setDocumentName(documentNameFor(chosen.name));
    }
  }

  function next() {
    if (step === 0) {
      // `problemWith` reports the first thing missing out of file, PDF, name,
      // signers — in that order. A stand-in signer row keeps it from
      // complaining about step 2's question while the reader is on step 1;
      // the real rows are checked on the way out of step 2 and again before
      // the call.
      const problem = problemWith(file, documentName, ["not asked yet"]);
      if (problem) {
        say(problem, "bad");
        return;
      }
    }
    if (step === 1 && signersFrom(rows).length === 0) {
      say("Name at least one person to sign it.", "bad");
      return;
    }
    clear();
    setStep(step + 1);
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
          say(
            result.status === 429
              ? "Too many attempts — please wait a few minutes and try again."
              : // The server's own message: it knows which address is
                // malformed, which account does not exist and how large a
                // file may be.
                (result.message ?? "The document could not be sent."),
            "bad",
          );
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

  // The server refuses to create a document for an account that has not
  // confirmed its address. Saying so here beats a form that fills in and
  // then fails.
  if (!me.emailVerified) {
    return (
      <AppShell me={me} narrow>
        <PageHeader
          title="Confirm your email first"
          description="Sending a document for signature needs a confirmed email address."
        />
        <SectionCard>
          <p className="text-base text-muted-foreground">
            Your account screen can send the confirmation link again.
          </p>
          <Button asChild size="lg" className="w-full">
            <Link href="/account">Go to my account</Link>
          </Button>
        </SectionCard>
      </AppShell>
    );
  }

  const signers = signersFrom(rows);

  return (
    <AppShell me={me} narrow>
      <PageHeader title="Send a document">
        <div className="space-y-3 pt-2">
          <Progress
            value={((step + 1) / STEPS.length) * 100}
            className="h-2"
            aria-label={`Step ${step + 1} of ${STEPS.length}`}
          />
          <ol className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
            {STEPS.map((label, index) => (
              <li
                key={label}
                aria-current={index === step ? "step" : undefined}
                className={cn(
                  index === step && "font-semibold text-primary",
                  index < step && "text-success-strong",
                  index > step && "text-muted-foreground",
                )}
              >
                {index < step ? (
                  <Check aria-hidden="true" className="mr-1 inline size-4" />
                ) : null}
                {index + 1}. {label}
              </li>
            ))}
          </ol>
        </div>
      </PageHeader>

      <SectionCard
        title={STEPS[step]}
        description={
          step === 1
            ? "An email address is invited to sign. A SignFlow username that already exists is given the document directly."
            : undefined
        }
      >
        {step === 0 ? (
          <>
            <div
              onDragOver={(event) => {
                event.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(event) => {
                event.preventDefault();
                setDragging(false);
                chooseFile(event.dataTransfer.files?.[0] ?? null);
              }}
              className={cn(
                "rounded-xl border-2 border-dashed p-6 text-center transition-colors duration-200",
                dragging ? "border-primary bg-accent" : "border-border bg-muted",
              )}
            >
              <Upload aria-hidden="true" className="mx-auto size-8 text-muted-foreground" />
              <p className="mt-3 text-base font-medium">
                Drag your PDF here, or choose it
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                A PDF. Nothing else can be signed.
              </p>
              <div className="mx-auto mt-4 max-w-xs">
                <Label htmlFor="file" className="sr-only">
                  Choose a PDF
                </Label>
                <Input
                  id="file"
                  name="file"
                  type="file"
                  accept="application/pdf,.pdf"
                  className="cursor-pointer bg-background file:mr-3 file:font-medium"
                  onChange={(event) => chooseFile(event.target.files?.[0] ?? null)}
                />
              </div>
            </div>

            {file ? (
              <p className="flex items-center gap-2 rounded-lg bg-success/10 p-3 text-base text-success-strong">
                <FileText aria-hidden="true" className="size-5 shrink-0" />
                <span className="break-all font-medium">{file.name}</span>
              </p>
            ) : null}

            <Field
              label="Document name"
              name="documentName"
              value={documentName}
              onChange={(value) => {
                setNamed(true);
                setDocumentName(value);
              }}
              hint="What everyone will see it called, and what it is downloaded as."
              disabled={busy}
            />
          </>
        ) : null}

        {step === 1 ? (
          <>
            <div className="space-y-4">
              {rows.map((row, index) => (
                <div key={index} className="flex items-end gap-3">
                  <Initials of={row || "?"} className="mb-1 hidden sm:flex" />
                  <div className="flex-1">
                    <Field
                      label={`Signer ${index + 1}`}
                      name={`signer-${index}`}
                      value={row}
                      placeholder="name@example.com"
                      onChange={(value) =>
                        setRows(rows.map((old, at) => (at === index ? value : old)))
                      }
                      required={false}
                      disabled={busy}
                    />
                  </div>
                  {rows.length > 1 ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="mb-1"
                      aria-label={`Remove signer ${index + 1}`}
                      onClick={() => setRows(rows.filter((_, at) => at !== index))}
                    >
                      <Trash2 aria-hidden="true" />
                    </Button>
                  ) : null}
                </div>
              ))}
            </div>

            <Button type="button" variant="outline" onClick={() => setRows([...rows, ""])}>
              <Plus aria-hidden="true" />
              Add another signer
            </Button>

            <Separator />

            <fieldset className="space-y-3">
              <legend className="mb-3 text-base font-medium">In what order?</legend>
              <RadioGroup value={policy} onValueChange={setPolicy} className="gap-3">
                {POLICIES.map((choice) => (
                  <Label
                    key={choice.value}
                    htmlFor={`policy-${choice.value}`}
                    className={cn(
                      "flex cursor-pointer items-start gap-3 rounded-xl border p-4 transition-colors duration-200",
                      policy === choice.value
                        ? "border-primary bg-accent"
                        : "border-border hover:bg-muted",
                    )}
                  >
                    <RadioGroupItem
                      id={`policy-${choice.value}`}
                      value={choice.value}
                      className="mt-0.5"
                    />
                    <span className="grid gap-0.5">
                      <span className="text-base font-medium">{choice.label}</span>
                      <span className="text-sm font-normal text-muted-foreground">
                        {choice.hint}
                      </span>
                    </span>
                  </Label>
                ))}
              </RadioGroup>
            </fieldset>
          </>
        ) : null}

        {step === 2 ? (
          <dl className="space-y-5 text-base">
            <div>
              <dt className="text-sm text-muted-foreground">Document</dt>
              <dd className="break-words font-medium">{documentName}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">
                {signers.length === 1 ? "Signer" : `Signers (${signers.length})`}
              </dt>
              <dd>
                <ol className="mt-2 space-y-2">
                  {signers.map((signer) => (
                    <li key={signer.order} className="flex items-center gap-3">
                      <Initials of={signer.userId} />
                      <span className="min-w-0">
                        <span className="block break-all">{signer.userId}</span>
                        <span className="block text-sm text-muted-foreground">
                          Signer {signer.order}
                        </span>
                      </span>
                    </li>
                  ))}
                </ol>
              </dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">Order</dt>
              <dd className="font-medium">
                {POLICIES.find((choice) => choice.value === policy)?.label}
              </dd>
            </div>
            <p className="rounded-lg bg-muted p-3 text-sm text-muted-foreground">
              Each signer is emailed a link. They sign in the SignFlow app on
              their own computer, with their own card — so a document waits
              for their card to be registered the first time.
            </p>
          </dl>
        ) : null}

        <NoteAlert note={note} />

        <div className="flex flex-col gap-3 sm:flex-row-reverse">
          {step < STEPS.length - 1 ? (
            <Button type="button" size="lg" className="sm:flex-1" onClick={next}>
              Next
              <ArrowRight aria-hidden="true" />
            </Button>
          ) : (
            <Button
              type="button"
              size="lg"
              className="sm:flex-1"
              disabled={busy}
              onClick={send}
            >
              {busy ? (
                <Busy>Sending…</Busy>
              ) : (
                <>
                  <SendIcon aria-hidden="true" />
                  Send for signature
                </>
              )}
            </Button>
          )}
          {step > 0 ? (
            <Button
              type="button"
              size="lg"
              variant="outline"
              disabled={busy}
              onClick={() => {
                clear();
                setStep(step - 1);
              }}
            >
              <ArrowLeft aria-hidden="true" />
              Back
            </Button>
          ) : null}
        </div>
      </SectionCard>

      <div className="mt-6">
        <Links>
          <TextLink href="/documents">Cancel and go back to my documents</TextLink>
        </Links>
      </div>
    </AppShell>
  );
}
