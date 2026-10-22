package com.example.signer.server.security;

import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * The response headers both filter chains send (security Phase 2 risk L9 and
 * Phase C risk C7 / Phase D1 risk D10).
 *
 * <h2>Referrer-Policy — risk L9</h2>
 *
 * <p>{@code no-referrer}, on every response. The pages behind an e-mailed
 * link carry a one-time token <b>in the URL</b>
 * ({@code /invitations/{token}}), so the default browser behaviour — send the
 * current URL in the {@code Referer} header of anything the page fetches, and
 * of any link the reader follows — would hand that token to a third party.
 * The invitation page links to a download site, so this is not hypothetical.
 *
 * <p>Applied everywhere rather than only to those pages: a header that is
 * only correct on the routes someone remembered to list is a header that will
 * be wrong on the next route added. Nothing here needs referrers.
 *
 * <h2>Content-Security-Policy — risk C7, carried into D1 as D10</h2>
 *
 * <p>{@code default-src 'none'} is the base, and every relaxation from it is
 * {@code 'self'} — which is exactly the right scope now that the website is
 * served from this application's own origin (D2). <b>Nothing external can be
 * loaded at all:</b> no CDN, no font service, no analytics, no image host.
 * That is a decision the same-origin choice makes cheap to keep, and it is
 * the directive to revisit if someone wants a hosted font.
 *
 * <p><b>There is no {@code 'unsafe-inline'} for script, and there must never
 * be one.</b> {@code script-src 'self'} permits files this server serves and
 * nothing else, so an injected {@code <script>} tag — the ordinary shape of
 * cross-site scripting — does not run even if some escaping is wrong
 * somewhere. The server-rendered invitation pages contain no script of their
 * own at all, and {@code SignPageRendererTests} holds them to that.
 *
 * <p>{@code style-src} keeps {@code 'unsafe-inline'} beside {@code 'self'},
 * and that is the one real concession here. {@code SignPageRenderer} puts its
 * stylesheet in an inline {@code <style>} block and uses two inline
 * {@code style=} attributes, and a nonce does not cover style attributes —
 * only {@code 'unsafe-inline'} does. What it permits is injected CSS, so the
 * worst case is a page made to look wrong rather than code running.
 *
 * <p>{@code form-action 'self'} because a form may post back here and must
 * not be redirected anywhere else; {@code base-uri 'none'} because no page
 * has a {@code <base>} and injecting one would re-point every relative URL;
 * {@code frame-ancestors 'none'} so nothing can be framed, which also covers
 * the browsers that prefer it to {@code X-Frame-Options}.
 *
 * <p>The same policy goes on the JSON APIs, where it is stricter than it
 * needs to be because a JSON response has nothing to load. One policy for
 * both chains is deliberate: two would drift, and the one that drifted would
 * be the one nobody was looking at.
 *
 * <h2>What Spring Security already sends</h2>
 *
 * <p>Neither chain configures {@code headers()}, so the defaults are in
 * force and are kept: {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY}, and {@code Cache-Control: no-cache,
 * no-store, max-age=0, must-revalidate} — the last of which matters for a
 * URL with a token in it. The tests assert those too, so that turning them
 * off cannot pass unnoticed.
 */
final class SecurityHeaders {

    /**
     * Allows nothing to be loaded, and no script to run under any
     * circumstances. Asserted character by character in
     * {@code SecurityHeadersHttpTests} so that a change to it is a change
     * someone made on purpose.
     */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';"
                    + " img-src 'self'; font-src 'self'; connect-src 'self';"
                    + " form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    private SecurityHeaders() {
    }

    /** Adds both headers to a chain, keeping Spring Security's defaults. */
    static void apply(HeadersConfigurer<?> headers) {
        headers
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .contentSecurityPolicy(csp -> csp
                        .policyDirectives(CONTENT_SECURITY_POLICY));
    }
}
