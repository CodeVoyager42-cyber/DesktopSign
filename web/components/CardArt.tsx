import styles from "./CardArt.module.css";

/**
 * The small drawing at the top of a feature card.
 *
 * A marketing page would use product screenshots here. Inline SVG instead,
 * for two reasons: a screenshot of a screen that is still being built would
 * be out of date the week after it was taken, and inline SVG is part of the
 * document, so it needs nothing from `img-src` and there is no second request
 * to wait for. Each one is a line drawing of the thing the card is about, in
 * the accent and the hairline, so they read as one set.
 */

export type ArtKind =
  | "card"
  | "people"
  | "trail"
  | "place"
  | "download"
  | "shield";

export function CardArt({ kind }: { kind: ArtKind }) {
  return (
    <div className={styles.art} aria-hidden="true">
      <svg viewBox="0 0 120 72" role="presentation" focusable="false">
        {ART[kind]}
      </svg>
    </div>
  );
}

const line = {
  fill: "none",
  stroke: "var(--line)",
  strokeWidth: 1.5,
} as const;

const accent = {
  fill: "none",
  stroke: "var(--accent)",
  strokeWidth: 1.75,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
};

const ART: Record<ArtKind, React.ReactNode> = {
  // A smart card with its chip, and the signature coming off it.
  card: (
    <>
      <rect x="14" y="18" width="52" height="34" rx="4" {...line} />
      <rect x="22" y="27" width="14" height="11" rx="2" {...accent} />
      <path d="M22 45h22" {...line} />
      <path d="M74 44c4-10 7-16 10-16s2 8 5 8 4-6 8-6" {...accent} />
      <path d="M72 52h38" {...line} />
    </>
  ),

  // Three signer slots, the first one done.
  people: (
    <>
      <circle cx="24" cy="28" r="8" {...accent} />
      <path d="M12 50c2-7 6-10 12-10s10 3 12 10" {...accent} />
      <circle cx="60" cy="30" r="7" {...line} />
      <path d="M49 50c2-6 5-9 11-9s9 3 11 9" {...line} />
      <circle cx="94" cy="30" r="7" {...line} />
      <path d="M83 50c2-6 5-9 11-9s9 3 11 9" {...line} />
    </>
  ),

  // An event trail: two done, one pending.
  trail: (
    <>
      <path d="M26 18v36" {...line} />
      <circle cx="26" cy="22" r="4.5" fill="var(--accent)" stroke="none" />
      <circle cx="26" cy="38" r="4.5" fill="var(--accent)" stroke="none" />
      <circle cx="26" cy="54" r="4.5" fill="var(--surface)" stroke="var(--line)" strokeWidth="1.5" />
      <path d="M38 22h58" {...line} />
      <path d="M38 38h44" {...line} />
      <path d="M38 54h30" {...line} />
    </>
  ),

  // A page with a signature box being placed on it.
  place: (
    <>
      <rect x="30" y="10" width="46" height="56" rx="3" {...line} />
      <path d="M38 22h30M38 30h30M38 38h18" {...line} />
      <rect x="44" y="44" width="38" height="16" rx="2" {...accent} />
      <path d="M50 55c3-5 5-8 7-8s1 4 3 4 2-3 4-3" {...accent} />
    </>
  ),

  // A finished document coming down.
  download: (
    <>
      <rect x="34" y="8" width="46" height="40" rx="3" {...line} />
      <path d="M42 20h30M42 28h30M42 36h18" {...line} />
      <path d="M57 44v18" {...accent} />
      <path d="M49 55l8 8 8-8" {...accent} />
      <path d="M30 66h54" {...line} />
    </>
  ),

  // A shield with a tick.
  shield: (
    <>
      <path d="M60 10l22 7v16c0 14-9 23-22 29-13-6-22-15-22-29V17z" {...line} />
      <path d="M50 36l7 7 14-15" {...accent} />
    </>
  ),
};
