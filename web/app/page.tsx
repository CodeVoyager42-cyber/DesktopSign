"use client";

import Link from "next/link";
import { useState } from "react";
import { SiteFooter, SiteHeader } from "../components/SiteChrome";
import { CardArt } from "../components/CardArt";
import styles from "./marketing.module.css";

/**
 * The public page.
 *
 * The structure is the one every enterprise signing product's front page
 * uses, because it is the order people expect to read: a minimal hero with
 * one action, proof, a tab filter over feature cards, how it works, the
 * security case, and a closing call to action.
 *
 * What fills it is only what this application actually does. There are no
 * customer logos, metrics or testimonials — inventing those would be
 * fabricating proof — so the strip under the hero carries verifiable
 * properties of the system instead, which is the honest version of the same
 * reassurance.
 */

const TABS = [
  {
    id: "featured",
    label: "Featured",
    cards: [
      {
        art: "card" as const,
        id: "signing",
        title: "Signing with a smart card",
        body: "The signature is made on your own computer by your own card."
          + " The private key never leaves it, and nothing is uploaded that"
          + " could stand in for it.",
        link: "How signing works",
        href: "#how",
      },
      {
        art: "people" as const,
        id: "requesting",
        title: "Requesting signatures",
        body: "Invite signers by e-mail address. Keep a fixed order, or let"
          + " whoever is free sign next — one at a time either way.",
        link: "Create an account",
        href: "/sign-up",
      },
      {
        art: "trail" as const,
        id: "tracking",
        title: "Tracking and audit trail",
        body: "Every step is recorded in order with who did it and when, so"
          + " the history is read from the record rather than guessed from"
          + " the current state.",
        link: "What is recorded",
        href: "#security",
      },
    ],
  },
  {
    id: "signing",
    label: "If you are signing",
    cards: [
      {
        art: "card" as const,
        id: "your-card",
        title: "Your certificate, your machine",
        body: "Insert the card, enter the PIN on your own computer, and the"
          + " document comes back signed. The PIN is never sent anywhere.",
        link: "How signing works",
        href: "#how",
      },
      {
        art: "place" as const,
        id: "placement",
        title: "You choose where it goes",
        body: "Place the signature on the page yourself, with a warning if it"
          + " would cover somebody else's.",
        link: "Create an account",
        href: "/sign-up",
      },
      {
        art: "download" as const,
        id: "copy",
        title: "Keep the finished copy",
        body: "When the last signer is done, everyone who took part can"
          + " download the signed PDF — including whoever sent it.",
        link: "Sign in",
        href: "/sign-in",
      },
    ],
  },
  {
    id: "sending",
    label: "If you are sending",
    cards: [
      {
        art: "people" as const,
        id: "send",
        title: "Send it once",
        body: "Upload the document, name the signers, and each gets a link of"
          + " their own that works once and expires.",
        link: "Create an account",
        href: "/sign-up",
      },
      {
        art: "trail" as const,
        id: "watch",
        title: "See where it stands",
        body: "One dashboard, grouped by what is waiting for you, what is"
          + " waiting for somebody else, and what is finished.",
        link: "Sign in",
        href: "/sign-in",
      },
      {
        art: "shield" as const,
        id: "control",
        title: "Change your mind",
        body: "Resend a link, revoke one, or cancel the whole request while it"
          + " is still in flight.",
        link: "What is recorded",
        href: "#security",
      },
    ],
  },
];

const PROPERTIES = [
  "Private key never leaves the card",
  "PKCS#11 smart cards and USB tokens",
  "Each revision kept, immutably",
  "Signatures verified before they are accepted",
  "Full audit trail per document",
];

const STEPS = [
  {
    title: "Send the document",
    body: "Upload a PDF and say who has to sign it. Signers who have no"
      + " account are invited by e-mail with a link that works once.",
  },
  {
    title: "Each signer opens it on their own computer",
    body: "The website hands over to the SignFlow desktop application, which"
      + " is where the card reader is. Nothing about the card is sent to the"
      + " server.",
  },
  {
    title: "They sign with their card",
    body: "Card in, PIN on their own keyboard, signature placed where they"
      + " choose. The signed revision goes back to the server, which checks"
      + " it before accepting it.",
  },
  {
    title: "Everyone gets the signed PDF",
    body: "Once the last signature is in, the finished document is there to"
      + " download for the sender and every signer.",
  },
];

const SECURITY = [
  {
    title: "The key stays on the card",
    body: "Signing happens on the signer's machine through PKCS#11. The"
      + " server never sees a private key or a PIN, and there is nothing in"
      + " an account that could sign on the holder's behalf.",
  },
  {
    title: "A signature is checked, not trusted",
    body: "A submitted revision is accepted only if it is an incremental"
      + " update of the exact revision it claims to follow, its whole"
      + " signature chain verifies, and the certificate is the one bound to"
      + " that slot.",
  },
  {
    title: "Nothing is revealed by accident",
    body: "A document you are not part of answers exactly as one that does"
      + " not exist. Sign-up, password reset and resend all answer the same"
      + " way whether or not the address is known.",
  },
  {
    title: "Your session is a cookie, not a token",
    body: "An HttpOnly, host-locked session cookie with a CSRF token on every"
      + " state-changing request. The page keeps no credential, because there"
      + " is none for it to keep.",
  },
];

export default function Home() {
  const [active, setActive] = useState(TABS[0].id);
  const shown = TABS.find((tab) => tab.id === active) ?? TABS[0];

  return (
    <>
      <SiteHeader />

      <div className={styles.banner}>
        <div className="wrap">
          Signed with the certificate on your own smart card — not with a
          password, and not with a key we hold.
        </div>
      </div>

      <main id="main">
        {/* Hero: one headline, one line, one primary action. */}
        <section className={styles.hero}>
          <div className="wrap">
            <h1 className={styles.heroTitle}>
              Get documents signed, properly.
            </h1>
            <p className={styles.heroLede}>
              Send a PDF for signature and have each person sign it with the
              certificate on their own smart card. You get a signed document
              that stands up to being checked.
            </p>
            <div className={styles.heroActions}>
              <Link href="/sign-up" className="button">
                Create an account
              </Link>
              <Link href="#how" className="button ghost">
                See how it works
              </Link>
            </div>
          </div>
        </section>

        {/* Where a marketing page would put customer logos. We have none, and
            inventing them would be inventing proof, so these are properties
            of the system that can be checked instead. */}
        <section className={styles.strip} aria-label="What the system guarantees">
          <div className={`wrap ${styles.stripRow}`}>
            {PROPERTIES.map((property) => (
              <span key={property} className={styles.property}>
                <Tick />
                {property}
              </span>
            ))}
          </div>
        </section>

        {/* The department-tab pattern, over the two roles that actually
            differ here: the person sending and the person signing. */}
        <section className={styles.section}>
          <div className="wrap">
            <div className={styles.tabs} role="tablist" aria-label="Who you are">
              {TABS.map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  role="tab"
                  id={`tab-${tab.id}`}
                  aria-selected={tab.id === active}
                  aria-controls={`panel-${tab.id}`}
                  className={
                    tab.id === active ? `${styles.tab} ${styles.tabOn}` : styles.tab
                  }
                  onClick={() => setActive(tab.id)}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            <div
              className={styles.cards}
              role="tabpanel"
              id={`panel-${shown.id}`}
              aria-labelledby={`tab-${shown.id}`}
            >
              {shown.cards.map((card) => (
                <article key={card.id} id={card.id} className={styles.feature}>
                  <CardArt kind={card.art} />
                  <h3>{card.title}</h3>
                  <p>{card.body}</p>
                  <Link href={card.href} className={styles.featureLink}>
                    {card.link} <span aria-hidden="true">›</span>
                  </Link>
                </article>
              ))}
            </div>
          </div>
        </section>

        {/* How it works. */}
        <section id="how" className={`${styles.section} ${styles.sunken}`}>
          <div className="wrap">
            <h2 className={styles.sectionTitle}>How it works</h2>
            <p className={styles.sectionLede}>
              Four steps, and the browser is never the thing doing the
              signing.
            </p>
            <ol className={styles.steps}>
              {STEPS.map((step, index) => (
                <li key={step.title} className={styles.step}>
                  <span className={styles.stepNumber} aria-hidden="true">
                    {index + 1}
                  </span>
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        {/* The security case, in the terms a reader cares about. */}
        <section id="security" className={styles.section}>
          <div className="wrap">
            <h2 className={styles.sectionTitle}>How it is kept safe</h2>
            <p className={styles.sectionLede}>
              Four decisions that do most of the work.
            </p>
            <div className={styles.pairs}>
              {SECURITY.map((item) => (
                <article key={item.title} className={styles.pair}>
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        {/* Closing call to action, full width. */}
        <section className={styles.closing}>
          <div className={`wrap ${styles.closingRow}`}>
            <div>
              <h2 className={styles.closingTitle}>
                Start sending documents for signature.
              </h2>
              <p className={styles.closingLede}>
                Creating an account takes an e-mail address and a password.
                You need a smart card only when it is your turn to sign.
              </p>
            </div>
            <div className={styles.closingActions}>
              <Link href="/sign-up" className="button">
                Create an account
              </Link>
              <Link href="/sign-in" className="button ghost">
                Sign in
              </Link>
            </div>
          </div>
        </section>
      </main>

      <SiteFooter />
    </>
  );
}

function Tick() {
  return (
    <svg
      className={styles.tick}
      viewBox="0 0 16 16"
      width="16"
      height="16"
      aria-hidden="true"
      focusable="false"
    >
      <path
        d="M3.5 8.5l3 3 6-7"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
