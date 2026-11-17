import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"
import { Slot } from "radix-ui"

/**
 * A 44 px tap target on a control that is deliberately drawn smaller.
 *
 * The rule is "touch targets ≥ 44 px" (design system §4.6) and the sizes
 * below do not all obey it — `sm` and `icon-sm` are 28 px and `icon-xs` is
 * 24 px. Growing them would be the wrong fix: a close cross in the corner of
 * a dialog and a Copy beside a line of checksum are drawn small because that
 * is what belongs there.
 *
 * So the target grows and the button does not. An empty `::before` centred on
 * the control gives it a 44 px hit area; hit-testing treats it as part of the
 * button, so a finger landing near the cross still presses it. Nothing moves
 * and nothing is painted.
 *
 * `tapY` is for a control already wide enough and only short — it stretches
 * the target vertically and leaves the sides alone, so it cannot swallow a
 * click meant for whatever sits next to it.
 */
const tap =
  "relative before:absolute before:left-1/2 before:top-1/2 before:size-11 before:-translate-x-1/2 before:-translate-y-1/2 before:content-['']"
const tapY =
  "relative before:absolute before:inset-x-0 before:top-1/2 before:h-11 before:-translate-y-1/2 before:content-['']"

const buttonVariants = cva(
  // The focus ring is the one in globals.css — `ring-2` with an offset —
  // rather than the preset's offsetless `ring-3 ring-ring/50`. A button that
  // focuses differently from the input above it reads as a different kind of
  // thing, and the offset is what keeps the ring legible against a filled
  // button.
  "group/button inline-flex shrink-0 items-center justify-center rounded-lg border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        // Pressed is a step darker than hover, not only the 1 px drop in the
        // base: on a touch screen there is no hover state at all, so without
        // this the only feedback for a press was a movement of one pixel.
        // Every step is mixed from the tokens themselves — a darker blue is
        // not a new colour to keep in step, and white on it can only gain
        // contrast on what already measures 8.72:1.
        default:
          "bg-primary text-primary-foreground hover:bg-primary-hover active:bg-[color-mix(in_oklch,var(--primary-hover),var(--foreground)_12%)]",
        outline:
          "border-border bg-background hover:bg-muted hover:text-foreground active:bg-[color-mix(in_oklch,var(--muted),var(--foreground)_6%)] aria-expanded:bg-muted aria-expanded:text-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-[color-mix(in_oklch,var(--secondary),var(--foreground)_5%)] active:bg-[color-mix(in_oklch,var(--secondary),var(--foreground)_9%)] aria-expanded:bg-secondary aria-expanded:text-secondary-foreground",
        ghost:
          "hover:bg-muted hover:text-foreground active:bg-[color-mix(in_oklch,var(--muted),var(--foreground)_6%)] aria-expanded:bg-muted aria-expanded:text-foreground dark:hover:bg-muted/50",
        destructive:
          "bg-destructive/10 text-destructive hover:bg-destructive/20 active:bg-destructive/25 focus-visible:ring-destructive dark:bg-destructive/20 dark:hover:bg-destructive/30 dark:focus-visible:ring-destructive/40",
        link: "text-primary underline-offset-4 hover:underline",
      },
      // Resized from the preset, which starts at h-8 (32px). `default`, `lg`
      // and `icon` are 44px or more in their own right, and their label is
      // 16px so a phone does not zoom when one sits next to a field.
      // Re-applied after `shadcn add sheet alert-dialog` pulled button in and
      // overwrote it.
      //
      // The smaller sizes are drawn smaller on purpose and carry `tap`/`tapY`
      // for the 44px target instead — see the note on those. This is what the
      // comment here used to claim outright: `sm` and `icon-sm` were 28px with
      // nothing making up the difference, and `icon-sm` is the cross that
      // closes the dialog and the mobile menu, which is a finger's job before
      // it is anything else.
      size: {
        default:
          "h-11 gap-2 px-5 text-base has-data-[icon=inline-end]:pr-4 has-data-[icon=inline-start]:pl-4",
        xs: `${tap} h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3`,
        sm: `${tapY} h-7 gap-1 rounded-[min(var(--radius-md),12px)] px-2.5 text-[0.8rem] in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3.5`,
        lg: "h-12 gap-2 px-6 text-base has-data-[icon=inline-end]:pr-5 has-data-[icon=inline-start]:pl-5",
        icon: "size-11",
        "icon-xs": `${tap} size-6 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3`,
        "icon-sm": `${tap} size-7 rounded-[min(var(--radius-md),12px)] in-data-[slot=button-group]:rounded-lg`,
        "icon-lg": `${tap} size-9`,
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
  }) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
