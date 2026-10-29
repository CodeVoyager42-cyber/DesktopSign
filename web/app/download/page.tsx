"use client";

import { useEffect, useState } from "react";
import {
  Check,
  Copy,
  CreditCard,
  Download,
  FileDown,
  Loader2,
  Monitor,
  PenLine,
  ShieldQuestion,
  Usb,
} from "lucide-react";
import { EmptyState, PageHeader, SectionCard } from "@/components/blocks";
import { SiteFooter, SiteHeader } from "@/components/chrome";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { api, type DownloadInfo } from "@/lib/api";
import { fileSize, groupedChecksum, versionLabel } from "@/lib/download";

/**
 * Where somebody gets the desktop application (Phase F, finding F2).
 *
 * <b>Public, and the only page of this website that is both public and
 * useful to a signed-in reader.</b> An invited signer has no account yet and
 * still needs the application before they can sign anything; telling them to
 * create an account first would be a circle. It reveals nothing — a version,
 * a size, a checksum — and the file it links to is public for the same
 * reason, bounded by a rate limit rather than a token.
 *
 * Everything on it comes from `GET /web/api/download-info`, which reads the
 * folder a release was published into. `available: false` is a normal state,
 * not an error: a deployment that has published nothing yet should say so.
 */

const STEPS = [
  {
    Icon: FileDown,
    title: "Download and open the file",
    detail: "Your browser will save it; open it when it finishes.",
  },
  {
    Icon: ShieldQuestion,
    title: "Install it",
    detail:
      "If Windows shows a blue warning, choose More info, then Run anyway. The installer is not yet signed, so Windows does not recognise it.",
  },
  {
    Icon: PenLine,
    title: "Plug in your card reader",
    detail:
      "Then come back to this website and press Sign on the document waiting for you.",
  },
];

const TROUBLES = [
  {
    question: "My browser asks “Open SignFlow?”",
    answer:
      "Allow it. That question is your browser checking that you meant to open the app, and it only appears because the app is installed and registered correctly.",
  },
  {
    question: "I press Sign and nothing happens",
    answer:
      "The app is probably not installed, or was installed before this page existed. Install it again from here, then restart your browser so it picks up the change.",
  },
  {
    question: "The app does not see my card",
    answer:
      "Check the reader is plugged in and that your card's own driver is installed — SignFlow uses the driver, it does not replace it. Your IT department or your card's issuer supplies it.",
  },
  {
    question: "Windows says the file is not commonly downloaded",
    answer:
      "That is SmartScreen, and it says so about every installer that is not code-signed. Compare the checksum below with the file you downloaded if you want to be sure of what you have.",
  },
];

/** The checksum, with a button that copies it. */
function Checksum({ sha256 }: { sha256: string }) {
  const [copied, setCopied] = useState(false);

  // Nothing is stored anywhere: the clipboard write is the whole action, and
  // the tick is a second of state in this component.
  function copy() {
    navigator.clipboard?.writeText(sha256).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      },
      () => setCopied(false),
    );
  }

  return (
    <div className="space-y-3">
      <p className="break-all rounded-lg bg-muted p-3 font-mono text-sm">
        {groupedChecksum(sha256)}
      </p>
      <Button type="button" variant="outline" onClick={copy}>
        {copied ? (
          <>
            <Check aria-hidden="true" />
            Copied
          </>
        ) : (
          <>
            <Copy aria-hidden="true" />
            Copy the checksum
          </>
        )}
      </Button>
      <p className="text-sm text-muted-foreground">
        To check the file you downloaded, open PowerShell in your Downloads
        folder and run{" "}
        <code className="rounded bg-muted px-1 py-0.5 font-mono text-sm">
          Get-FileHash .\{"{file}"} -Algorithm SHA256
        </code>
        . The value it prints should match the one above, ignoring spaces and
        capitals.
      </p>
    </div>
  );
}

export default function DownloadPage() {
  const [info, setInfo] = useState<DownloadInfo | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    api.downloadInfo().then((result) => {
      if (!live) return;
      if (result.ok && result.body) {
        setInfo(result.body);
      } else {
        setFailed(true);
      }
    });
    return () => {
      live = false;
    };
  }, []);

  const appName = info?.appName ?? "SignFlow";
  const size = fileSize(info?.sizeBytes ?? 0);
  const version = versionLabel(info?.version);

  return (
    <div className="flex min-h-dvh flex-col">
      <SiteHeader />

      <main id="main" className="container-page flex-1 py-8 sm:py-10">
        <PageHeader
          title={`Get ${appName} for Windows`}
          description={`You need the ${appName} app on your computer to sign with your smart card. Sending and following documents works in the browser.`}
        />

        <div className="grid gap-6 lg:grid-cols-3">
          <div className="space-y-6 lg:col-span-2">
            {/* The one action on the page. */}
            <SectionCard>
              {info === null && !failed ? (
                <div className="space-y-3">
                  <Skeleton className="h-12 w-64" />
                  <Skeleton className="h-5 w-40" />
                </div>
              ) : null}

              {failed || (info && !info.available) ? (
                <EmptyState
                  icon={Download}
                  title="The download isn't available yet"
                  description="Contact your administrator — they publish the installer for your organisation."
                />
              ) : null}

              {info?.available && info.url ? (
                <>
                  <Button asChild size="lg" className="w-full sm:w-auto">
                    {/* A real anchor, not a route: the browser downloads it. */}
                    <a href={info.url}>
                      <Download aria-hidden="true" />
                      Download for Windows
                    </a>
                  </Button>
                  <p className="text-base text-muted-foreground">
                    {[version, size].filter(Boolean).join(" · ")}
                    {info.fileName ? (
                      <>
                        {version || size ? " · " : ""}
                        <span className="break-all">{info.fileName}</span>
                      </>
                    ) : null}
                  </p>
                  {info.portableUrl ? (
                    <p className="text-base">
                      <Button asChild variant="link" className="h-auto px-0 text-base">
                        <a href={info.portableUrl}>
                          Or download the portable version
                          {info.portableSizeBytes
                            ? ` (${fileSize(info.portableSizeBytes)})`
                            : ""}
                        </a>
                      </Button>
                      <span className="block text-sm text-muted-foreground">
                        A zip you can unpack anywhere. It does not register
                        itself, so the Sign button on this website cannot open
                        it — you would start it yourself each time.
                      </span>
                    </p>
                  ) : null}
                  <Alert className="shadow-sm">
                    <ShieldQuestion aria-hidden="true" />
                    <AlertDescription className="text-base">
                      The installer is not code-signed yet, so Windows
                      SmartScreen will warn about it. Choose{" "}
                      <span className="font-medium">More info</span>, then{" "}
                      <span className="font-medium">Run anyway</span>.
                    </AlertDescription>
                  </Alert>
                </>
              ) : null}
            </SectionCard>

            <SectionCard title="How to install it">
              <ol className="space-y-5">
                {STEPS.map((step, index) => (
                  <li key={step.title} className="flex gap-4">
                    <div className="flex size-11 shrink-0 items-center justify-center rounded-full bg-accent">
                      <step.Icon
                        aria-hidden="true"
                        className="size-5 text-accent-foreground"
                      />
                    </div>
                    <div className="space-y-1">
                      <h3>
                        <span className="text-muted-foreground">{index + 1}. </span>
                        {step.title}
                      </h3>
                      <p className="text-base text-muted-foreground">{step.detail}</p>
                    </div>
                  </li>
                ))}
              </ol>
            </SectionCard>

            <SectionCard
              title="Something went wrong?"
              description="The four things that usually explain it."
            >
              <Accordion type="single" collapsible className="w-full">
                {TROUBLES.map((trouble, index) => (
                  <AccordionItem key={trouble.question} value={`trouble-${index}`}>
                    <AccordionTrigger className="text-left text-base">
                      {trouble.question}
                    </AccordionTrigger>
                    <AccordionContent className="text-base text-muted-foreground">
                      {trouble.answer}
                    </AccordionContent>
                  </AccordionItem>
                ))}
              </Accordion>
            </SectionCard>
          </div>

          <div className="space-y-6">
            <SectionCard title="What you need" icon={Monitor}>
              <ul className="space-y-3 text-base">
                <li className="flex gap-3">
                  <Monitor aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
                  Windows 10 or 11, 64-bit
                </li>
                <li className="flex gap-3">
                  <Usb aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
                  A smart card reader
                </li>
                <li className="flex gap-3">
                  <CreditCard aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
                  Your card&apos;s own driver installed
                </li>
              </ul>
              <p className="text-sm text-muted-foreground">
                Nothing else: the app brings its own Java runtime.
              </p>
            </SectionCard>

            {info?.sha256 ? (
              <SectionCard
                title="Check the file (optional)"
                description="So you can be sure the file you have is the file we published."
              >
                <Checksum sha256={info.sha256} />
              </SectionCard>
            ) : null}

            <Card className="shadow-sm">
              <CardContent className="flex gap-3 py-2">
                <Loader2 aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
                <p className="text-base text-muted-foreground">
                  Already installed? Go to your documents and press{" "}
                  <span className="font-medium text-foreground">Sign now</span>{" "}
                  on the one waiting for you.
                </p>
              </CardContent>
            </Card>
          </div>
        </div>
      </main>

      <SiteFooter />
    </div>
  );
}
