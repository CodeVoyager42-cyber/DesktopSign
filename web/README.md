# web — the SignFlow website

Next.js 15 (App Router) and TypeScript, built to static files and served by
the Spring Boot application from its own origin.

```bash
npm install
npm run sync      # next build, then copy the export into the server's resources
npm test          # the dashboard grouping logic, in Node's own test runner
npm run dev       # :3000, for layout and copy only — see the caveat
```

`npm run sync` is the one that matters: it builds, wipes
`../src/main/resources/static`, and copies the export there. That output is
committed, so a server build and `mvn test` need no Node.

**`npm run dev` caveat:** the dev server is a different origin from the API,
so the session cookie will not reach it and anything behind sign-in will not
work. For those screens, `npm run sync` and restart the server.

Everything else — why it is an export rather than a Next.js server, how the
Content-Security-Policy handles the inline scripts, how routing is wired into
Spring, and what is tested — is in **`../docs/frontend-nextjs.md`**. The
visual system is **`../docs/frontend-design.md`**.
