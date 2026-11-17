"use client";

import { PageHeader } from "@/components/blocks";
import { AppShell } from "@/components/chrome";
import { DownloadRelease } from "@/components/download-release";
import { SessionPending, useSession } from "@/components/session";
import { UploadAndSign } from "@/components/upload-sign";

/**
 * Choose a PDF and sign it.
 *
 * <p><b>What this page used to be.</b> The dashboard: three tabs — "To sign"
 * with a count, "Sent by me", and "Shared with me" when it had anything in it
 * — over a table on a desktop and cards on a phone, merged from the two list
 * endpoints Phase E provides because which group a document belongs in comes
 * from the server's view of the caller's own slot. That is all gone, replaced
 * by the upload, which is what this screen is now asked to be.
 *
 * <p><b>What went with it, and is worth knowing.</b> The lists carried the
 * Download button for a finished document, so a signed PDF is now reachable
 * only from {@code /open} immediately after signing, while that page still
 * has the workflow in hand. There is no screen left that enumerates what this
 * account has signed. The server has not changed — the list endpoints are
 * still there and still answer — so putting a list back is a screen, not a
 * feature.
 *
 * <p>This is the only screen that uploads anything. {@code /send} was the
 * same upload and has been removed — its path is no longer in
 * {@code FrontendRoutingConfiguration.PAGES}, so it answers 404 rather than
 * serving a page that is not in the export. The upload itself is
 * {@link UploadAndSign}.
 *
 * <p>The download section stays: somebody about to sign needs the
 * application, and somebody who has not signed yet may still want it
 * installed before the moment comes.
 */
export default function Documents() {
  const { me, state } = useSession();

  if (state !== "in" || !me) {
    return <SessionPending />;
  }

  return (
    <AppShell me={me}>
      <PageHeader title="Sign a document">
        <p className="pt-1 text-base text-muted-foreground">
          Choose a PDF, then sign it with your smart card. The signing happens
          in the app on this computer — the card never leaves your hands.
        </p>
      </PageHeader>

      {/* Two columns from `lg`, the same 2:1 split `/download` uses, so the
          upload keeps a readable measure instead of stretching across a
          6xl page and the app card sits beside it rather than below the
          fold. One column below that, upload first: on a phone the thing
          you came to do should not be under a card about installing
          something. */}
      <div className="grid items-start gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <UploadAndSign me={me} />
        </div>
        <aside className="lg:col-span-1">
          <DownloadRelease />
        </aside>
      </div>
    </AppShell>
  );
}
