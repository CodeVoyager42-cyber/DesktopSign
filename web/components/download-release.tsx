"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  Check,
  CircleAlert,
  Copy,
  CreditCard,
  Download,
  FileDown,
  ShieldCheck,
} from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { SectionCard } from "@/components/blocks";
import { api, type LatestRelease, type ReleaseAsset } from "@/lib/api";
import { fileSize, groupedChecksum, releaseDate, verifyCommand } from "@/lib/download";

/**
 * The dashboard's download section: the newest release, fetched live.
 *
 * It replaces the old static "you need the app" alert with the real thing.
 * The data comes from `GET /web/api/latest-release`, which the server
 * answers from GitHub (refreshed at most once an hour) or, when GitHub is
 * not configured, from its own download folder. Either way every link
 * points at this server, so the private repository never appears in a
 * browser URL.
 *
 * The card mirrors the public `/download` page's wording where the two
 * overlap — SmartScreen note, checksum verification, smartcard setup — so
 * a reader who sees both never meets two different stories about the same
 * file.
 */

/** A checksum with a button that copies it, the same pattern as /download. */
function ChecksumCopy({ sha256 }: { sha256: string }) {
  const [copied, setCopied] = useState(false);

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
    <div className="flex flex-wrap items-center gap-2">
      <code className="break-all rounded-lg bg-muted p-2 font-mono text-sm">
        {groupedChecksum(sha256)}
      </code>
      <Button type="button" variant="outline" size="sm" onClick={copy}>
        {copied ? (
          <>
            <Check aria-hidden="true" />
            Copied
          </>
        ) : (
          <>
            <Copy aria-hidden="true" />
            Copy
          </>
        )}
      </Button>
    </div>
  );
}

/** One download button: primary for the installer, outline for the zip. */
function AssetButton({
  asset,
  variant,
  label,
}: {
  asset: ReleaseAsset;
  variant: "default" | "outline";
  label: string;
}) {
  const size = fileSize(asset.sizeBytes);
  return (
    <Button asChild size={variant === "default" ? "lg" : "default"} variant={variant}>
      <a href={asset.url ?? "#"}>
        {variant === "default" ? (
          <Download aria-hidden="true" />
        ) : (
          <FileDown aria-hidden="true" />
        )}
        {label}
        {size ? <span className="font-normal text-muted-foreground">· {size}</span> : null}
      </a>
    </Button>
  );
}

/** What the security accordion explains, per artefact. */
function VerificationGuide({ asset }: { asset: ReleaseAsset }) {
  const command = verifyCommand(asset.fileName);
  return (
    <div className="space-y-3 text-sm text-muted-foreground">
      <p>
        Windows SmartScreen may show a blue warning when you open the
        installer. That happens for every application that is not code-signed
        — choose <b>More info</b>, then <b>Run anyway</b>.
      </p>
      {asset.sha256 ? (
        <>
          <p>
            To be sure of what you downloaded, compare the file against the
            checksum below. It should match the value published with the
            release.
          </p>
          <ChecksumCopy sha256={asset.sha256} />
          {command ? (
            <p>
              Open PowerShell in your Downloads folder and run{" "}
              <code className="rounded bg-muted px-1 py-0.5 font-mono text-sm">
                {command}
              </code>
              . The value it prints should match the one above, ignoring spaces
              and capitals.
            </p>
          ) : null}
          {asset.checksumUrl ? (
            <p>
              You can also download the official{" "}
              <a
                href={asset.checksumUrl}
                className="font-medium text-primary underline underline-offset-4"
              >
                .sha256 file
              </a>{" "}
              and compare it with the hash you obtained.
            </p>
          ) : null}
        </>
      ) : (
        <p>
          The checksum for this file is not available yet. It will appear as
          soon as the release finishes publishing.
        </p>
      )}
    </div>
  );
}

/** The eBarid Sign smartcard guide, carried over from the download page. */
function SmartcardGuide() {
  const steps = [
    "Install the eBarid Sign middleware (Gemalto driver) from the token manufacturer.",
    "Plug the smartcard or USB token into the computer before opening SignFlow.",
    "In SignFlow, choose the certificate when signing — v0.2.1 detects eTPKCS11.dll and gclib.dll in System32 automatically.",
    "Enter the token PIN when Windows asks for it, and the signature is applied.",
  ];
  return (
    <ol className="list-decimal space-y-2 pl-5 text-sm text-muted-foreground">
      {steps.map((step) => (
        <li key={step}>{step}</li>
      ))}
    </ol>
  );
}

export function DownloadRelease() {
  const [release, setRelease] = useState<LatestRelease | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    api.latestRelease().then((result) => {
      if (!active) return;
      if (result.ok && result.body) {
        setRelease(result.body);
      } else {
        setFailed(true);
      }
    });
    return () => {
      active = false;
    };
  }, []);

  const loading = !release && !failed;

  return (
    <SectionCard
      title="Get the SignFlow app"
      description="The desktop application you need to sign documents on this computer."
      icon={Download}
    >
      {loading ? (
        <div className="space-y-3">
          <Skeleton className="h-5 w-48" />
          <div className="flex flex-wrap gap-3">
            <Skeleton className="h-11 w-64" />
            <Skeleton className="h-10 w-44" />
          </div>
        </div>
      ) : failed ? (
        <div className="space-y-3">
          <Alert variant="destructive">
            <CircleAlert aria-hidden="true" />
            <AlertDescription>
              We could not load the download right now. Try again in a moment.
            </AlertDescription>
          </Alert>
          <Button asChild variant="outline">
            <Link href="/download">Open the download page</Link>
          </Button>
        </div>
      ) : !release?.available ? (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">
            There is no release published yet. When there is one, it appears
            here automatically.
          </p>
          <Button asChild variant="outline">
            <Link href="/download">Open the download page</Link>
          </Button>
        </div>
      ) : (
        <div className="space-y-5">
          <p className="text-base">
            <span className="font-medium">Latest Version:</span>{" "}
            {release.version ? (
              <span className="font-mono">v{release.version}</span>
            ) : (
              <span className="text-muted-foreground">unknown</span>
            )}
            {release.publishedAt ? (
              <span className="text-muted-foreground">
                {" "}
                · published {releaseDate(release.publishedAt)}
              </span>
            ) : null}
          </p>

          <div className="flex flex-wrap items-center gap-3">
            {release.installer?.url ? (
              <AssetButton
                asset={release.installer}
                variant="default"
                label="Download for Windows (.msi)"
              />
            ) : null}
            {release.portable?.url ? (
              <AssetButton
                asset={release.portable}
                variant="outline"
                label="Portable ZIP"
              />
            ) : null}
            {!release.installer?.url && !release.portable?.url ? (
              <Button asChild variant="outline">
                <Link href="/download">Open the download page</Link>
              </Button>
            ) : null}
          </div>

          <Accordion type="single" collapsible>
            <AccordionItem value="security">
              <AccordionTrigger>
                <span className="flex items-center gap-2">
                  <ShieldCheck aria-hidden="true" className="size-4 text-primary" />
                  Verify the file before running it
                </span>
              </AccordionTrigger>
              <AccordionContent className="space-y-4">
                {release.installer?.url ? (
                  <VerificationGuide asset={release.installer} />
                ) : null}
                {release.portable?.url ? (
                  <div className="space-y-2">
                    <p className="text-sm font-medium text-foreground">
                      Portable ZIP
                    </p>
                    <VerificationGuide asset={release.portable} />
                  </div>
                ) : null}
              </AccordionContent>
            </AccordionItem>
            <AccordionItem value="smartcard">
              <AccordionTrigger>
                <span className="flex items-center gap-2">
                  <CreditCard aria-hidden="true" className="size-4 text-primary" />
                  Setting up your eBarid Sign smartcard
                </span>
              </AccordionTrigger>
              <AccordionContent>
                <SmartcardGuide />
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </div>
      )}
    </SectionCard>
  );
}
