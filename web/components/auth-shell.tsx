"use client";

import { Brand, SiteFooter } from "@/components/chrome";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/**
 * The shape of every account screen: the logo, a centred card on a soft blue
 * band, a title, one short sentence, the fields, one blue button, and a link
 * or two underneath.
 *
 * One shell rather than seven page layouts, because these screens differ
 * only in their fields — and because somebody who has just created an
 * account and is now confirming an address should not feel they have moved
 * to a different website between the two. There is no header nav here on
 * purpose: on a sign-in screen the only thing to do is sign in.
 */
export function AuthShell({
  title,
  lede,
  children,
  below,
}: {
  title: string;
  lede?: string;
  children: React.ReactNode;
  below?: React.ReactNode;
}) {
  return (
    <div className="band-soft flex min-h-dvh flex-col">
      <main
        id="main"
        className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center gap-6 px-4 py-10"
      >
        <div className="flex justify-center">
          <Brand />
        </div>
        <Card className="shadow-sm">
          <CardHeader>
            <CardTitle className="text-2xl">{title}</CardTitle>
            {lede ? (
              <CardDescription className="text-base">{lede}</CardDescription>
            ) : null}
          </CardHeader>
          <CardContent className="space-y-5">{children}</CardContent>
        </Card>
        {below}
      </main>
      <SiteFooter />
    </div>
  );
}
