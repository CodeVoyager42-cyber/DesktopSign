"use client";

import Link from "next/link";
import type { LucideIcon } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn } from "@/lib/utils";

/**
 * The four blocks every screen is assembled from.
 *
 * They exist so that two screens showing the same kind of thing cannot drift
 * apart: a heading is a `PageHeader` everywhere, an empty list is an
 * `EmptyState` everywhere, and a destructive action is a `ConfirmDialog`
 * everywhere. Anything a page needs that is not here is a sign this file
 * should grow, not that the page should improvise.
 */

/**
 * A screen's title, one line about it, and the single action worth a blue
 * button — which sits on the right on a wide screen and underneath on a
 * phone, because at 375 px a title and a button side by side leaves room
 * for neither.
 */
export function PageHeader({
  title,
  description,
  action,
  children,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  children?: React.ReactNode;
}) {
  return (
    <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0 space-y-2">
        <h1 className="break-words">{title}</h1>
        {description ? (
          <p className="text-base text-muted-foreground">{description}</p>
        ) : null}
        {children}
      </div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  );
}

/**
 * Nothing here yet, and what to do about it.
 *
 * Always a sentence and, where there is one, a button: a panel that only says
 * "no documents" tells somebody they are in the right place and leaves them
 * there.
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <Card className="border-dashed shadow-none">
      <CardContent className="flex flex-col items-center gap-4 px-6 py-12 text-center">
        <div className="flex size-12 items-center justify-center rounded-full bg-accent">
          <Icon aria-hidden="true" className="size-6 text-accent-foreground" />
        </div>
        <div className="space-y-1">
          <h2>{title}</h2>
          {description ? (
            <p className="text-base text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {action}
      </CardContent>
    </Card>
  );
}

/**
 * A titled card, used the same way on every screen: one subject, an optional
 * sentence under the title, the content, and a footer only when there is an
 * action that belongs to the card rather than the page.
 */
export function SectionCard({
  title,
  description,
  icon: Icon,
  footer,
  className,
  children,
}: {
  title?: string;
  description?: string;
  icon?: LucideIcon;
  footer?: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <Card className={cn("shadow-sm", className)}>
      {title ? (
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            {Icon ? (
              <Icon aria-hidden="true" className="size-5 text-primary" />
            ) : null}
            {title}
          </CardTitle>
          {description ? (
            <CardDescription className="text-base">{description}</CardDescription>
          ) : null}
        </CardHeader>
      ) : null}
      <CardContent className="space-y-5">{children}</CardContent>
      {footer ? <CardFooter className="pt-0">{footer}</CardFooter> : null}
    </Card>
  );
}

/**
 * Asking before something irreversible.
 *
 * An `AlertDialog` rather than a second inline panel: it takes focus, closes
 * on Escape, and names the consequence in its own body — and it is the same
 * dialog for cancelling a document as for withdrawing an invitation, so the
 * reader learns the shape once. `destructive` is the default because
 * everything that needs confirming here is.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel = "Keep it",
  busy,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  busy?: boolean;
  onConfirm: () => void;
}) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription className="text-base">
            {description}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>{cancelLabel}</AlertDialogCancel>
          <AlertDialogAction
            disabled={busy}
            onClick={onConfirm}
            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
          >
            {confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * Somebody's initials, for a signer row.
 *
 * From an email address or a username, because that is all the API gives for
 * a signer: `anna@example.com` becomes AN. No image is ever loaded — there is
 * nowhere to load one from under `img-src 'self'`, and a signer has no avatar
 * in this product.
 */
export function Initials({ of, className }: { of: string; className?: string }) {
  const name = of.split("@")[0] ?? of;
  const parts = name.split(/[._\-\s]+/).filter(Boolean);
  const letters =
    parts.length > 1
      ? `${parts[0][0]}${parts[1][0]}`
      : name.slice(0, 2);

  return (
    <Avatar className={cn("size-9", className)}>
      <AvatarFallback className="bg-accent text-sm font-medium text-accent-foreground">
        {letters.toUpperCase()}
      </AvatarFallback>
    </Avatar>
  );
}

/** A row of quiet links under a card. */
export function Links({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-2 gap-y-1">
      {children}
    </div>
  );
}

/**
 * A link that looks like a link: the Button's `link` variant for the styling,
 * `next/link` underneath for client-side navigation, and a real anchor in the
 * markup either way.
 */
export function TextLink({
  href,
  children,
}: {
  href: string;
  children: React.ReactNode;
}) {
  return (
    <Button asChild variant="link" className="h-auto px-1 py-0 text-base">
      <Link href={href}>{children}</Link>
    </Button>
  );
}
