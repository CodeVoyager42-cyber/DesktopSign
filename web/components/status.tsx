import {
  CheckCircle2,
  CircleAlert,
  CircleSlash,
  Clock,
  CreditCard,
  Loader2,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { STATE_WORDS } from "@/lib/dashboard";
import { cn } from "@/lib/utils";

/**
 * A document's state, in words, in colour, and with an icon — all three,
 * never colour alone.
 *
 * The words come from `STATE_WORDS` in `lib/dashboard.ts`, which stays the
 * one place a server state is turned into English. This file adds only what
 * belongs to the look: which icon, and which of the four tones.
 *
 * Each tone is its own colour at 10 % behind text in the `-strong` step of
 * the same hue. The lighter `--success` and `--warning` are AA on white but
 * only 4.4:1 on their own tint, which is exactly where a badge puts them;
 * `--success-strong` and `--warning-strong` measure 6.2 there.
 */

type Tone = "done" | "waiting" | "attention" | "stopped";

const TONES: Record<Tone, string> = {
  done: "bg-success/10 text-success-strong",
  waiting: "bg-primary/10 text-primary",
  attention: "bg-warning/10 text-warning-strong",
  stopped: "bg-destructive/10 text-destructive",
};

const STATES: Record<string, { tone: Tone; Icon: typeof Clock }> = {
  COMPLETED: { tone: "done", Icon: CheckCircle2 },
  CANCELLED: { tone: "stopped", Icon: CircleSlash },
  FAILED: { tone: "stopped", Icon: CircleAlert },
  // A draft is waiting for the signers to register the card they will sign
  // with, which is why its icon is a card and not a clock.
  DRAFT: { tone: "attention", Icon: CreditCard },
  READY_FOR_SIGNING: { tone: "waiting", Icon: Clock },
  WAITING_FOR_SIGNER: { tone: "waiting", Icon: Clock },
  SIGNATURE_SUBMITTED: { tone: "waiting", Icon: Loader2 },
  VALIDATING: { tone: "waiting", Icon: Loader2 },
};

export function StatusBadge({
  status,
  className,
}: {
  status: string;
  className?: string;
}) {
  const known = STATES[status];
  const tone = known?.tone ?? "waiting";
  const Icon = known?.Icon ?? Clock;
  return (
    <Badge
      variant="secondary"
      className={cn(
        "gap-1.5 rounded-full border-transparent px-2.5 py-1 text-sm font-medium",
        TONES[tone],
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-4" />
      {/* An unknown state is shown as it came rather than hidden: the server
          may grow one, and a screen that dropped it would be wrong in the
          direction that hides things. */}
      {STATE_WORDS[status] ?? status}
    </Badge>
  );
}

/** The same three tones for one signer's progress on a document. */
export function SignerBadge({
  state,
  words,
}: {
  state: "signed" | "current" | "waiting";
  words: string;
}) {
  const tone: Tone =
    state === "signed" ? "done" : state === "current" ? "waiting" : "attention";
  const Icon =
    state === "signed" ? CheckCircle2 : state === "current" ? Clock : Clock;
  return (
    <Badge
      variant="secondary"
      className={cn(
        "gap-1.5 rounded-full border-transparent px-2.5 py-1 text-sm font-medium",
        state === "waiting" ? "bg-muted text-muted-foreground" : TONES[tone],
      )}
    >
      <Icon aria-hidden="true" className="size-4" />
      {words}
    </Badge>
  );
}
