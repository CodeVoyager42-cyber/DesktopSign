"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  ChevronDown,
  Download,
  FileSignature,
  FileText,
  KeyRound,
  LogOut,
  Menu,
  Send,
  UserCog,
} from "lucide-react";
import { Initials } from "@/components/blocks";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import { api, type Profile } from "@/lib/api";

/**
 * The header and footer every page wears.
 *
 * One bar, three slots: who we are, where you can go, and the one action
 * worth a blue button. On a phone the links move into a Sheet behind a single
 * menu button, so the bar never wraps into two rows of half-hit targets.
 */

export function Brand({ className }: { className?: string }) {
  return (
    <Link
      href="/"
      aria-label="SignFlow home"
      className={`flex items-center gap-2 rounded-lg text-lg font-semibold tracking-tight text-foreground ${className ?? ""}`}
    >
      <FileSignature aria-hidden="true" className="size-6 text-primary" />
      <span>
        <span className="text-primary">Sign</span>Flow
      </span>
    </Link>
  );
}

function Bar({ children }: { children: React.ReactNode }) {
  return (
    <header className="sticky top-0 z-40 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80">
      <div className="container-page flex h-16 items-center gap-3">{children}</div>
    </header>
  );
}

function signOut() {
  api.signOut().then(() => window.location.assign("/sign-in"));
}

/** The account menu, on a screen wide enough for one. */
function AccountMenu({ me }: { me: Profile }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="gap-2 px-2" aria-label={`Account: ${me.username}`}>
          <Initials of={me.email ?? me.username} className="size-8" />
          <span className="hidden max-w-28 truncate sm:inline">{me.username}</span>
          <ChevronDown aria-hidden="true" className="hidden sm:inline" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60 shadow-md">
        <DropdownMenuLabel className="font-normal">
          <span className="block text-sm font-medium">{me.username}</span>
          <span className="block truncate text-sm text-muted-foreground">
            {me.email}
          </span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link href="/account">
            <UserCog aria-hidden="true" />
            Account
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <Link href="/change-password">
            <KeyRound aria-hidden="true" />
            Change password
          </Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={signOut}>
          <LogOut aria-hidden="true" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** The same places, on a phone, in a Sheet. */
function MobileMenu({ me }: { me: Profile | null }) {
  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button variant="ghost" size="icon" className="sm:hidden" aria-label="Menu">
          <Menu aria-hidden="true" />
        </Button>
      </SheetTrigger>
      <SheetContent side="right" className="w-72">
        <SheetHeader>
          <SheetTitle>
            <Brand />
          </SheetTitle>
        </SheetHeader>
        <nav className="mt-6 flex flex-col gap-2 px-4" aria-label="Menu">
          {me ? (
            <>
              <SheetClose asChild>
                <Button asChild variant="ghost" className="justify-start">
                  <Link href="/documents">
                    <FileText aria-hidden="true" />
                    Documents
                  </Link>
                </Button>
              </SheetClose>
              <SheetClose asChild>
                <Button asChild variant="ghost" className="justify-start">
                  <Link href="/download">
                    <Download aria-hidden="true" />
                    Download app
                  </Link>
                </Button>
              </SheetClose>
              <SheetClose asChild>
                <Button asChild className="justify-start">
                  <Link href="/send">
                    <Send aria-hidden="true" />
                    Send a document
                  </Link>
                </Button>
              </SheetClose>
              <SheetClose asChild>
                <Button asChild variant="ghost" className="justify-start">
                  <Link href="/account">
                    <UserCog aria-hidden="true" />
                    Account
                  </Link>
                </Button>
              </SheetClose>
              <SheetClose asChild>
                <Button asChild variant="ghost" className="justify-start">
                  <Link href="/change-password">
                    <KeyRound aria-hidden="true" />
                    Change password
                  </Link>
                </Button>
              </SheetClose>
              <Button variant="ghost" className="justify-start" onClick={signOut}>
                <LogOut aria-hidden="true" />
                Sign out
              </Button>
            </>
          ) : (
            <>
              <SheetClose asChild>
                <Button asChild variant="ghost" className="justify-start">
                  <Link href="/sign-in">Sign in</Link>
                </Button>
              </SheetClose>
              <SheetClose asChild>
                <Button asChild className="justify-start">
                  <Link href="/sign-up">Create account</Link>
                </Button>
              </SheetClose>
            </>
          )}
        </nav>
      </SheetContent>
    </Sheet>
  );
}

/** What a signed-in reader sees. */
export function AppHeader({ me }: { me: Profile | null }) {
  return (
    <Bar>
      <Brand />
      <div className="ml-auto flex items-center gap-1 sm:gap-2">
        <Button asChild variant="ghost" className="hidden sm:inline-flex">
          <Link href="/download">
            <Download aria-hidden="true" />
            Download app
          </Link>
        </Button>
        <Button asChild variant="ghost" className="hidden sm:inline-flex">
          <Link href="/documents">Documents</Link>
        </Button>)
        <Button asChild className="hidden sm:inline-flex">
          <Link href="/send">
            <Send aria-hidden="true" />
            Send a document
          </Link>
        </Button>
        {me ? (
          <>
            <div className="hidden sm:block">
              <AccountMenu me={me} />
            </div>
            <MobileMenu me={me} />
          </>
        ) : null}
      </div>
    </Bar>
  );
}

/**
 * The public header. Whether there is a session is resolved in the browser,
 * because a static page cannot know — and the server is the authority on it
 * anyway, so it is asked rather than guessed from a cookie this code cannot
 * read. Nothing fills the right-hand slot until the answer is in: offering
 * "Create account" to somebody already signed in, then swapping it, is worse
 * than a short gap.
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

  if (asked && me) {
    return <AppHeader me={me} />;
  }

  return (
    <Bar>
      <Brand />
      <div className="ml-auto flex items-center gap-1 sm:gap-2" aria-busy={!asked}>
        {asked ? (
          <>
            <Button asChild variant="ghost" className="hidden sm:inline-flex">
              <Link href="/download">
                <Download aria-hidden="true" />
                Download app
              </Link>
            </Button>
            <Button asChild variant="ghost" className="hidden sm:inline-flex">
              <Link href="/sign-in">Sign in</Link>
            </Button>
            <Button asChild className="hidden sm:inline-flex">
              <Link href="/sign-up">Create account</Link>
            </Button>
            <MobileMenu me={null} />
          </>
        ) : null}
      </div>
    </Bar>
  );
}

export function SiteFooter() {
  return (
    <footer className="mt-auto border-t border-border">
      <div className="container-page flex flex-col gap-2 py-6 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
        <p>
          SignFlow — documents signed with the certificate on your own smart
          card.
        </p>
        <nav aria-label="Footer" className="flex gap-4">
          <Link href="/download" className="underline-offset-4 hover:underline">
            Download app
          </Link>
          <Link href="/sign-in" className="underline-offset-4 hover:underline">
            Sign in
          </Link>
          <Link href="/sign-up" className="underline-offset-4 hover:underline">
            Create account
          </Link>
        </nav>
      </div>
    </footer>
  );
}

/** Every signed-in screen is this shape: bar, content, footer. */
export function AppShell({
  me,
  children,
  narrow,
}: {
  me: Profile | null;
  children: React.ReactNode;
  narrow?: boolean;
}) {
  return (
    <div className="flex min-h-dvh flex-col">
      <AppHeader me={me} />
      <main
        id="main"
        className={`flex-1 py-8 sm:py-10 ${narrow ? "container-narrow" : "container-page"}`}
      >
        {children}
      </main>
      <SiteFooter />
    </div>
  );
}
