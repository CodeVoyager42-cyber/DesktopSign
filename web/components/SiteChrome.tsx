"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { api, type Profile } from "../lib/api";
import styles from "./SiteChrome.module.css";

/**
 * The header and footer every page wears.
 *
 * The shape follows the pattern the enterprise signing products all use — a
 * brand, a product menu that opens on hover or focus, a couple of flat links,
 * and the sign-in pair pushed to the right — because it is the shape people
 * already know how to read. What goes in it is this application's own: the
 * menu lists what SignFlow actually does, and there is no pricing link
 * because there is no pricing.
 */

export function Brand() {
  return (
    <Link href="/" className={styles.brand} aria-label="SignFlow home">
      <span className={styles.mark}>Sign</span>
      <span>Flow</span>
    </Link>
  );
}

const PRODUCT_MENU = [
  {
    href: "/#signing",
    title: "Signing with a smart card",
    detail: "The certificate stays on the card; the desktop app does the signing.",
  },
  {
    href: "/#requesting",
    title: "Requesting signatures",
    detail: "Invite signers by e-mail, in order or in any order.",
  },
  {
    href: "/#tracking",
    title: "Tracking and audit trail",
    detail: "Every step recorded, in order, with who did it and when.",
  },
  {
    href: "/#security",
    title: "How it is kept safe",
    detail: "Sessions, revisions and what the server refuses to do.",
  },
];

/**
 * The product menu. A `<details>` element rather than a hand-rolled dropdown:
 * it opens with the keyboard, closes on Escape and needs no ARIA invented for
 * it, and the click-outside handler below is the only script involved.
 */
function ProductMenu() {
  const menu = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    function closeOnOutside(event: MouseEvent) {
      if (menu.current && !menu.current.contains(event.target as Node)) {
        menu.current.open = false;
      }
    }
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape" && menu.current) {
        menu.current.open = false;
      }
    }
    document.addEventListener("click", closeOnOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("click", closeOnOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, []);

  return (
    <details className={styles.menu} ref={menu}>
      <summary className={styles.menuLabel}>Product</summary>
      <div className={styles.menuPanel}>
        {PRODUCT_MENU.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={styles.menuItem}
            onClick={() => {
              if (menu.current) menu.current.open = false;
            }}
          >
            <span className={styles.menuItemTitle}>{item.title}</span>
            <span className={styles.menuItemDetail}>{item.detail}</span>
          </Link>
        ))}
      </div>
    </details>
  );
}

/**
 * The marketing header. `me` is resolved in the browser, because a static
 * page cannot know — and the server is the authority on whether there is a
 * session anyway, so it is asked rather than guessed from a cookie this code
 * cannot read.
 */
export function SiteHeader() {
  const [me, setMe] = useState<Profile | null>(null);
  const [asked, setAsked] = useState(false);

  useEffect(() => {
    let live = true;
    api.me().then((result) => {
      if (!live) return;
      setMe(result.ok ? result.body : null);
      setAsked(true);
    });
    return () => {
      live = false;
    };
  }, []);

  return (
    <header className={styles.header}>
      <div className={`wrap ${styles.headerRow}`}>
        <Brand />
        <nav className={styles.nav} aria-label="Product">
          <ProductMenu />
          <Link href="/#how" className={styles.navLink}>
            How it works
          </Link>
          <Link href="/#security" className={styles.navLink}>
            Security
          </Link>
        </nav>
        {/* Nothing is rendered in the sign-in slot until the server has
            answered: offering "Create account" to somebody who is already
            signed in, and then swapping it, is worse than a short gap. */}
        <div className={styles.actions} aria-busy={!asked}>
          {asked && me ? (
            <>
              <Link href="/documents" className={styles.navLink}>
                Documents
              </Link>
              <Link href="/account" className="button ghost">
                {me.username}
              </Link>
            </>
          ) : null}
          {asked && !me ? (
            <>
              <Link href="/sign-in" className={styles.navLink}>
                Sign in
              </Link>
              <Link href="/sign-up" className="button">
                Create an account
              </Link>
            </>
          ) : null}
        </div>
      </div>
    </header>
  );
}

/** The header the signed-in screens wear: the same bar, fewer decisions. */
export function AppHeader({ me }: { me: Profile | null }) {
  return (
    <header className={styles.header}>
      <div className={`wrap ${styles.headerRow}`}>
        <Brand />
        <nav className={styles.nav} aria-label="Your account">
          <Link href="/documents" className={styles.navLink}>
            Documents
          </Link>
        </nav>
        <div className={styles.actions}>
          {me ? (
            <Link href="/account" className="button ghost">
              {me.username}
            </Link>
          ) : null}
        </div>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={`wrap ${styles.footerRow}`}>
        <p className={styles.footerLine}>
          SignFlow — documents signed with a certificate on your own smart
          card.
        </p>
        <nav className={styles.footerLinks} aria-label="Footer">
          <Link href="/sign-in">Sign in</Link>
          <Link href="/sign-up">Create an account</Link>
          <Link href="/#security">Security</Link>
        </nav>
      </div>
    </footer>
  );
}
