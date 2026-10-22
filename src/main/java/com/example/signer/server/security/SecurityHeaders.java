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
 * <p>{@code default-src 'none'} and no relaxation for script, which is the
 * part that matters: the server-rendered pages contain no script at all, so
 * <b>none is permitted</b>, and injected script would not run even if some
 * escaping were wrong.
 *
 * <p>{@code style-src 'unsafe-inline'} is the one concession, and it is
 * honest rather than ideal. {@code SignPageRenderer} puts its stylesheet in
 * an inline {@code <style>} block and uses two inline {@code style=}
 * attributes, and a nonce does not cover style attributes — only
 * {@code 'unsafe-inline'} does. What it permits is injected CSS, so the worst
 * case is the page being made to look wrong, not code running. If a script
 * ever belongs on one of these pages, this is the directive to revisit, with
 * a nonce.
 *
 * <p>{@code form-action 'none'} and {@code base-uri 'none'} because these
 * pages have neither a form nor a {@code <base>}; {@code frame-ancestors
 * 'none'} so they cannot be framed, which also covers the browsers that
 * prefer it to {@code X-Frame-Options}.
 *
 * <p>The same policy goes on the JSON APIs, where it is stricter still
 * because a JSON response has no styles to apply. One policy for both chains
 * is deliberate: two would drift, and the one that drifted would be the one
 * nobody was looking at.
 *
 * <p><b>D2 will have to widen this.</b> A real frontend needs at least
 * {@code script-src 'self'}, {@code style-src 'self'}, {@code img-src 'self'}
 * and {@code connect-src 'self'}. Starting strict and relaxing deliberately
 * is the right direction to approach that from; starting permissive and
 * hoping to tighten later is how a CSP ends up meaning nothing.
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
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'none';"
                    + " base-uri 'none'; frame-ancestors 'none'";

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
