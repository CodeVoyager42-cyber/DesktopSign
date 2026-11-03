import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Join class names and let the later Tailwind utility win.
 *
 * `clsx` flattens strings, arrays, objects and conditionals; `twMerge` then
 * drops the earlier of any two classes that set the same property, which is
 * what makes `<Button className="h-12">` able to override a variant's `h-11`
 * instead of landing in an undefined order.
 *
 * The shadcn CLI generated this file as a one-line re-export from its own
 * `cn` package, which bundles the same two libraries and compiles their
 * lookup tables ahead of time. It is a fine package and it is not what this project
 * keeps: `clsx` + `tailwind-merge` is the pairing every shadcn component in
 * the wild is written against, so anything copied from the documentation
 * behaves the way its author expected, and two well-known dependencies are
 * easier to reason about than one that re-implements both.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
