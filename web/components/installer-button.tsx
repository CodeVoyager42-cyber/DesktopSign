"use client";

import { useEffect, useRef, useState } from "react";
import { CircleAlert, Download } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Busy } from "@/components/session";
import { api } from "@/lib/api";
import { installerFailure } from "@/lib/download";

/**
 * The one button that gets the desktop application.
 *
 * <b>Two requests, not one</b>, and that is the whole design. The file is
 * handed to the browser as a navigation (`api.installerUrl()`), because that
 * is the only way a 200 MB installer never passes through this code — no
 * blob, no `URL.createObjectURL`, no copy in memory, and the browser's own
 * download shelf showing progress and offering a resume. But a navigation
 * cannot be taken back, so the status is asked for first with a HEAD of the
 * same route (`api.installerReady()`), and only a 200 starts the download.
 *
 * That preflight is also what makes the states honest:
 *
 *  - <b>401</b> — no session, so the reader is sent to `/sign-in`, the same
 *    way `useSession` sends them. The server decided this, not the page: a
 *    page is not an authorisation, and the route refuses an anonymous caller
 *    whatever this code believes.
 *  - <b>404</b> — the deployment has published no installer. A normal state,
 *    not a fault, and the message says who publishes one.
 *  - <b>429</b> — the shared per-address download limit.
 *  - anything else, including an unreachable server — one generic line.
 *
 * <b>Why a disabled button and not a guard alone.</b> `busy` disables it
 * while the preflight is in flight, so a double click cannot send two
 * requests; the ref is what actually prevents the second one, because a
 * click handler can run again before React has re-rendered with the new
 * state. Both are needed: the ref is correctness, the disabled attribute is
 * what the reader sees.
 */
export function InstallerButton({
  label = "Download for Windows",
  size = "lg",
}: {
  label?: string;
  size?: "default" | "lg";
}) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  // The guard a second click actually hits: state is asynchronous, this is
  // not, so this is what makes the handler re-entrant-safe.
  const running = useRef(false);
  // Set on unmount, so nothing is written to a component that has gone.
  const live = useRef(true);

  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);

  async function start() {
    if (running.current) {
      return;
    }
    running.current = true;
    setBusy(true);
    setFailure(null);

    const ready = await api.installerReady();

    if (ready.status === 401) {
      // No session. Leave `busy` set: the page is on its way out, and a
      // button that springs back to life first looks like the click failed.
      window.location.replace("/sign-in");
      return;
    }

    if (!live.current) {
      return;
    }

    if (ready.ok) {
      // The download itself. A navigation to an `attachment` response does
      // not unload the page — the browser takes the file and leaves the
      // dashboard exactly as it is.
      window.location.assign(api.installerUrl());
    } else {
      setFailure(installerFailure(ready.status));
    }

    // Released either way, so a reader whose download failed can try again,
    // and one whose download started can fetch it a second time.
    running.current = false;
    setBusy(false);
  }

  return (
    <div className="space-y-3">
      <Button
        type="button"
        size={size}
        onClick={start}
        disabled={busy}
        className="w-full sm:w-auto"
      >
        {busy ? (
          <Busy>Starting the download…</Busy>
        ) : (
          <>
            <Download aria-hidden="true" />
            {label}
          </>
        )}
      </Button>

      {/* Announced, not only coloured, like every other message here. */}
      <div role="status" aria-live="polite">
        {failure ? (
          <Alert variant="destructive" className="shadow-sm">
            <CircleAlert aria-hidden="true" />
            <AlertDescription className="text-base">{failure}</AlertDescription>
          </Alert>
        ) : null}
      </div>
    </div>
  );
}
