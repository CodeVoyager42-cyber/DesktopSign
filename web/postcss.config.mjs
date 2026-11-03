/**
 * Tailwind v4 runs as a PostCSS plugin, which is all the setup it needs:
 * there is no tailwind.config.js in v4, because the theme is declared in CSS
 * (see app/globals.css).
 *
 * Both builds read this: `next build` (what `npm run sync` runs, and what
 * produces the export that ships) and `next dev --turbopack`.
 */
const config = {
  plugins: {
    "@tailwindcss/postcss": {},
  },
};

export default config;
