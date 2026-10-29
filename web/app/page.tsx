"use client";

import Link from "next/link";
import { CreditCard, FileUp, PenLine, ShieldCheck, Users } from "lucide-react";
import { SiteFooter, SiteHeader } from "@/components/chrome";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

/**
 * The public page.
 *
 * It replaces a long marketing page — hero, proof strip, role tabs over
 * feature cards, how it works, the security case, a closing call to action —
 * with a headline, three steps and two buttons. Somebody arriving here has
 * either been sent a document or wants to send one; neither needs persuading,
 * and the page that tried to do both was the longest thing in the website.
 */

const STEPS = [
  {
    Icon: FileUp,
    title: "Upload your PDF",
    detail: "Choose the document you want signed.",
  },
  {
    Icon: Users,
    title: "Add the signers",
    detail: "Their email addresses, in order or in any order.",
  },
  {
    Icon: PenLine,
    title: "Everyone signs",
    detail: "Each person signs on their own computer, with their own card.",
  },
];

const PROMISES = [
  {
    Icon: ShieldCheck,
    lead: "Nothing is signed in the browser.",
    detail:
      "Signing happens in the SignFlow app on your computer, with your card in the reader.",
  },
  {
    Icon: CreditCard,
    lead: "You keep the key.",
    detail:
      "The certificate stays on the card. This website never sees it, and neither does anyone else.",
  },
];

export default function Home() {
  return (
    <div className="flex min-h-dvh flex-col">
      <SiteHeader />

      <main id="main" className="flex-1">
        {/* The one gradient in the website, fading into the page so it has no
            visible edge. */}
        <section className="band-soft">
          <div className="container-page py-16 text-center sm:py-24">
            <h1 className="text-balance text-3xl font-semibold tracking-tight sm:text-5xl">
              Sign documents securely with your smart card
            </h1>
            <p className="mx-auto mt-5 max-w-xl text-pretty text-lg text-muted-foreground">
              Send a PDF, see who still has to sign, and sign with the
              certificate on your own card.
            </p>
            <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
              <Button asChild size="lg" className="w-full sm:w-auto">
                <Link href="/sign-up">Create an account</Link>
              </Button>
              <Button asChild size="lg" variant="outline" className="w-full sm:w-auto">
                <Link href="/sign-in">Sign in</Link>
              </Button>
            </div>
          </div>
        </section>

        <section aria-labelledby="how" className="container-page py-14 sm:py-20">
          <h2 id="how" className="text-center text-xl font-semibold sm:text-2xl">
            How it works
          </h2>
          <ol className="mt-10 grid gap-5 sm:grid-cols-3">
            {STEPS.map((step, index) => (
              <li key={step.title}>
                <Card className="h-full shadow-sm transition-shadow duration-200 hover:shadow-md">
                  <CardContent className="space-y-3 py-2">
                    <div className="flex size-11 items-center justify-center rounded-full bg-accent">
                      <step.Icon
                        aria-hidden="true"
                        className="size-5 text-accent-foreground"
                      />
                    </div>
                    <h3>
                      <span className="text-muted-foreground">{index + 1}. </span>
                      {step.title}
                    </h3>
                    <p className="text-base text-muted-foreground">{step.detail}</p>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ol>
        </section>

        <section className="border-t border-border bg-muted/40">
          <div className="container-page grid gap-6 py-12 sm:grid-cols-2 sm:py-16">
            {PROMISES.map((promise) => (
              <div key={promise.lead} className="flex gap-3">
                <promise.Icon
                  aria-hidden="true"
                  className="mt-0.5 size-5 shrink-0 text-primary"
                />
                <p className="text-base text-muted-foreground">
                  <span className="font-medium text-foreground">{promise.lead}</span>{" "}
                  {promise.detail}
                </p>
              </div>
            ))}
          </div>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
