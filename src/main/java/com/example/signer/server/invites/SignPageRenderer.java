package com.example.signer.server.invites;

/**
 * Renders the public signing page served at {@code /sign/{token}} — the page
 * the invitation email links to.
 *
 * <p>Pure rendering (no Spring, no database) so every state can be
 * unit-tested. Nothing database-internal is ever shown: the page exposes the
 * document name, the inviter, the invited address and the expiry only.
 *
 * <p>The markup is a single self-contained HTML document (no JavaScript, no
 * external assets) with a mobile-first stylesheet: full-width touch targets
 * and stacked metadata on phones, comfortable two-column metadata on larger
 * screens.
 *
 * <p>Light only, exactly like the Next.js website (web/app/globals.css):
 * every colour below is that file's measured token, so a page rendered here
 * sits beside the site's shadcn screens without a seam. There is no dark
 * variant on purpose — see the note in globals.css before adding one.
 */
public final class SignPageRenderer {

    /** Everything the page needs about a live invitation. */
    public record View(String documentName, String inviter, String invitedEmail,
                       String expiresAt, String deepLinkUrl, String documentUrl,
                       String appName, boolean accountExists,
                       /** Configured installer link, or null/blank when unset. */
                       String downloadUrl) {

        /** Backward-compatible constructor for existing call sites/tests. */
        public View(String documentName, String inviter, String invitedEmail,
                    String expiresAt, String deepLinkUrl, String documentUrl,
                    String appName, boolean accountExists) {
            this(documentName, inviter, invitedEmail, expiresAt, deepLinkUrl,
                    documentUrl, appName, accountExists, null);
        }
    }

    private SignPageRenderer() {
    }

    /*
     * Every value here is the matching token from the website's design
     * system, web/app/globals.css (same name, same measurement section).
     * Light only — the site renders no dark theme, so neither does this.
     */
    private static final String STYLE = """
            :root{--bg:#f8fafc;--card:#ffffff;--ink:#0f172a;
                  --muted:#475569;--line:#e2e8f0;
                  --brand:#1d4ed8;--brand-hover:#1e40af;--brand-ink:#ffffff;
                  --soft:#f8fafc;--accent:#eff6ff;--ring:#3b82f6;
                  --ok:#15803d;--ok-bg:#eef6ee;
                  --warn:#b45309;--warn-bg:#fdf3dd;
                  --bad:#b91c1c;--bad-bg:#fbe9e9}
            *{box-sizing:border-box}
            body{font:16px/1.6 "Inter Variable",-apple-system,BlinkMacSystemFont,
                       "Segoe UI",Roboto,Helvetica,Arial,sans-serif;
                 background:var(--bg);color:var(--ink);margin:0;
                 padding:24px 14px 40px}
            .card{max-width:640px;margin:0 auto;background:var(--card);
                  border:1px solid var(--line);
                  border-radius:12px;box-shadow:0 1px 3px rgba(15,23,42,.06);
                  padding:30px clamp(18px,5vw,36px) 26px}
            .top{display:flex;align-items:center;justify-content:space-between;
                 gap:10px;flex-wrap:wrap;margin-bottom:6px}
            .brand{font-size:13px;font-weight:600;letter-spacing:.4px;
                   text-transform:uppercase;color:var(--muted)}
            .pill{display:inline-block;font-size:12.5px;font-weight:500;
                  padding:4px 12px;border-radius:999px;background:var(--warn-bg);
                  color:var(--warn);white-space:nowrap}
            .pill--info{background:var(--accent);color:var(--brand)}
            .pill--ok{background:var(--ok-bg);color:var(--ok)}
            .pill--bad{background:var(--bad-bg);color:var(--bad)}
            h1{margin:2px 0 4px;font-size:clamp(21px,5vw,25px);color:var(--ink);
               font-weight:600;letter-spacing:-0.2px;
               line-height:1.25;overflow-wrap:anywhere}
            .sub{color:var(--muted);margin:0 0 22px;font-size:15px}
            .steps{display:flex;gap:6px;margin:0 0 22px;padding:0;list-style:none;
                   flex-wrap:wrap}
            .steps li{flex:1 1 0;min-width:88px;font-size:12.5px;
                      color:var(--muted);border-top:2px solid var(--line);
                      padding-top:7px}
            .steps li.done{border-top-color:var(--ok);color:var(--ok)}
            .steps li.now{border-top-color:var(--brand);color:var(--brand);
                          font-weight:600}
            dl{margin:0 0 24px}
            dl > div{display:flex;gap:14px;padding:9px 0;
                     border-bottom:1px solid var(--line)}
            dt{color:var(--muted);flex:0 0 38%;font-size:14px;margin:0}
            dd{margin:0;font-size:14.5px;font-weight:500;min-width:0;
               overflow-wrap:anywhere}
            .cta{display:flex;align-items:center;justify-content:center;gap:9px;
                 background:var(--brand);color:var(--brand-ink);
                 text-decoration:none;padding:14px 26px;border-radius:10px;
                 font-weight:500;font-size:16px;min-height:50px;text-align:center}
            .cta:hover{background:var(--brand-hover)}
            .cta:focus-visible{outline:2px solid var(--ring);
                               outline-offset:2px}
            a:not(.cta):focus-visible{outline:2px solid var(--ring);
                                      outline-offset:2px}
            .cta.secondary{background:var(--accent);color:var(--brand);
                           font-weight:500;margin-top:10px}
            .cta.secondary:hover{background:#dbeafe}
            .row{margin:0 0 10px}
            .note{color:var(--muted);font-size:13px;margin:18px 0 0;
                  overflow-wrap:anywhere}
            .detail{font-size:15px;margin:0 0 6px;color:var(--muted)}
            .install{border:1px solid var(--line);border-radius:12px;
                     padding:14px 16px;margin-top:22px;background:var(--soft)}
            .install-head{margin:0 0 8px;font-size:14px;font-weight:600}
            @media (max-width:460px){
              body{padding:14px 10px 28px}
              .card{padding:22px 16px 20px}
              dl > div{flex-direction:column;gap:2px;padding:10px 0}
              dt{flex:none}
              .cta{width:100%;padding-left:12px;padding-right:12px}
              .steps li{min-width:74px}
            }
            """;

    /** Live invitation: review details, document access and the sign action. */
    public static String pending(View v) {
        // Invited signers need no account: the button opens this one signing
        // task in SignFlow directly (a guest signing session).
        String account = "Secure invitation — no SignFlow account is required."
                + " The button opens this signing task directly in SignFlow.";
        return page("""
                <div class="top">
                    <span class="brand">%s</span>
                    <span class="pill">Awaiting your signature</span>
                </div>
                <h1>Signature required</h1>
                <p class="sub">You have been invited to sign a document.</p>
                <ol class="steps">
                  <li class="done">1 · Invited</li>
                  <li class="now">2 · Open in SignFlow</li>
                  <li>3 · Signed</li>
                </ol>
                <dl>
                  <div><dt>Document</dt><dd>%s</dd></div>
                  <div><dt>Requested by</dt><dd>%s</dd></div>
                  <div><dt>Your email</dt><dd>%s</dd></div>
                  <div><dt>Link valid until</dt><dd>%s</dd></div>
                </dl>
                <p class="row"><a class="cta" href="%s">Review &amp; Sign — opens SignFlow</a></p>
                %s
                <p class="note">%s</p>
                <div class="install">
                  <p class="install-head">Don't have %s yet?</p>
                  %s
                  <p class="note">Already installed? The button above opens SignFlow
                  straight onto this invitation — no need to search for the document.</p>
                </div>
                <p class="note">Your signature is produced on your own device with your
                signing token — the key and its PIN never leave it.</p>
                """.formatted(esc(v.appName()), esc(v.documentName()),
                esc(v.inviter()), esc(v.invitedEmail()), esc(v.expiresAt()),
                attr(v.deepLinkUrl()), documentLink(v.documentUrl()), esc(account),
                esc(v.appName()), installSection(v)));
    }

    /**
     * Optional browser review link. The coordination server no longer serves
     * the PDF to bearer-token holders (P0.10), so the server passes
     * {@code null} and the document is reviewed in the desktop app after
     * sign-in; the parameter stays for callers that render their own link.
     */
    private static String documentLink(String documentUrl) {
        if (documentUrl == null || documentUrl.isBlank()) {
            return "";
        }
        return "<p class=\"row\"><a class=\"cta secondary\" href=\""
                + attr(documentUrl) + "\">View the document</a></p>";
    }

    /** Friendly outcome page for expired / used / revoked / unknown tokens. */
    public static String notice(String heading, String detail, String appName) {
        return notice(heading, detail, appName, toneFor(heading));
    }

    /** Same page with an explicit badge tone: "info", "ok", "warn" or "bad". */
    public static String notice(String heading, String detail, String appName,
                                String tone) {
        return page("""
                <div class="top">
                    <span class="brand">%s</span>
                    <span class="pill pill--%s">%s</span>
                </div>
                <h1>%s</h1>
                <p class="detail">%s</p>
                <p class="note">If you were expecting a signing request, ask the sender
                to invite you again — they can resend a fresh link at any time.</p>
                """.formatted(esc(appName), esc(tone), esc(heading), esc(heading),
                esc(detail)));
    }

    /** Shared responsive shell around a page body. */
    private static String page(String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light">
                <style>%s</style></head><body>
                <div class="card">
                %s
                </div></body></html>
                """.formatted(STYLE, body);
    }

    /** Maps the controller's headings to a badge tone. */
    private static String toneFor(String heading) {
        if (heading == null) {
            return "info";
        }
        return switch (heading) {
            case "Already accepted" -> "ok";
            case "Link expired" -> "warn";
            case "Link withdrawn", "Link not valid" -> "bad";
            default -> "info";
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * The not-installed fallback (brief Scenario 3): a download button when
     * an installer URL is configured, otherwise an honest note pointing at
     * the sender/IT team — never a dead end, never a fake link.
     */
    private static String installSection(View v) {
        String url = v.downloadUrl() == null ? "" : v.downloadUrl().trim();
        if (url.isEmpty()) {
            return "<p class=\"note\" style=\"margin:0\">SignFlow Desktop is"
                    + " distributed by your organisation — contact the sender"
                    + " for the installer.</p>";
        }
        return "<p class=\"row\" style=\"margin:0\"><a class=\"cta\""
                + " href=\"" + attr(url) + "\">Download SignFlow</a></p>";
    }

    private static String attr(String s) {
        return esc(s).replace("'", "&#39;");
    }
}
