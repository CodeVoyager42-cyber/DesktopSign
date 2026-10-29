package com.example.signer.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 risk L9 and Phase C risk C7 (carried into Phase D1 as
 * D10): the response headers, against a real server.
 *
 * <p>L9 is specifically about the pages behind an e-mailed link, which carry
 * a one-time token <b>in the URL</b> — so the first test is the invitation
 * page, and what it asserts is that the token cannot leave in a
 * {@code Referer} header.
 *
 * <p>The policy is asserted character by character on purpose. It is short,
 * it is security-relevant, and a change to it should be a change someone
 * made deliberately rather than one that slipped through because a test only
 * checked that <i>some</i> policy was present.
 */
@SpringBootTest(classes = com.example.signer.server.WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
class SecurityHeadersHttpTests {

    /**
     * The policy both chains send, after {@code script-src}. Restated here
     * because this is its spec.
     *
     * <p>{@code script-src} itself is checked separately, because it is the
     * one directive that is not a fixed string: it is {@code 'self'} plus a
     * {@code sha256-} expression for each inline script in the exported
     * website (see {@code InlineScriptHashes}). What matters about it is
     * asserted below — {@code 'self'}, hashes, and no {@code 'unsafe-inline'}
     * — rather than its exact length, which is a property of the frontend
     * build.
     */
    private static final String POLICY_AFTER_SCRIPTS =
            "style-src 'self' 'unsafe-inline';"
                    + " img-src 'self'; font-src 'self'; connect-src 'self';"
                    + " form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("security-headers-store").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static final HttpClient http = HttpClient.newHttpClient();

    private String base;

    @LocalServerPort
    void capturePort(int port) {
        this.base = "http://localhost:" + port;
    }

    /* ------------------------------------------------------------------ */

    @Test
    void theInvitationPathSendsNoReferrerBecauseItsUrlHoldsAToken() throws Exception {
        // Risk L9, on the very path it names. The page itself needs a
        // database (SignPageController is conditional on one), so in file
        // mode this route answers without rendering it — which does not
        // weaken the test, because the header is a property of the FILTER
        // CHAIN and not of the handler. The rendered page is covered by
        // SignPageRendererTests, and the web chain by WebAccountHttpTests.
        HttpResponse<String> page = get("/invitations/not-a-real-token");

        assertThat(page.headers().firstValue("Referrer-Policy"))
                .contains("no-referrer");
        assertThat(page.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(policy -> assertThat(policy)
                        .contains("script-src 'self' 'sha256-")
                        .endsWith(POLICY_AFTER_SCRIPTS));
    }

    @Test
    void theJsonApiSendsThemToo() throws Exception {
        // Not because a JSON response can load anything, but because a
        // header that is only right on the routes someone remembered to list
        // will be wrong on the next route added.
        HttpResponse<String> health = get("/api/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(health.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(policy -> assertThat(policy)
                        .endsWith(POLICY_AFTER_SCRIPTS));
    }

    @Test
    void anUnauthenticatedRefusalCarriesThemAsWell() throws Exception {
        // The 401 path goes through JsonSecurityErrors rather than a
        // controller, so it is worth checking that it has not bypassed the
        // header writer.
        HttpResponse<String> refused = get("/api/workflows/my-tasks");

        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(refused.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(policy -> assertThat(policy)
                        .endsWith(POLICY_AFTER_SCRIPTS));
    }

    @Test
    void thePolicyPermitsNoScriptAtAll() throws Exception {
        // The whole point of it. default-src 'none' with nothing relaxing
        // script means injected script does not run even if some escaping
        // were wrong somewhere.
        String policy = get("/invitations/not-a-real-token")
                .headers().firstValue("Content-Security-Policy").orElseThrow();

        assertThat(policy).contains("default-src 'none'");
        assertThat(policy)
                .as("script comes from this origin only — an injected <script> tag,"
                        + " which is the ordinary shape of XSS, cannot run")
                .contains("script-src 'self'")
                .doesNotContain("'unsafe-inline' 'self'")
                .doesNotContain("unsafe-eval");

        // The website's own inline scripts are permitted one at a time, by
        // hash, and nothing else inline is. A hash names one exact script, so
        // this stays true of an injected one. If this list is ever replaced by
        // 'unsafe-inline' the assertion below is what should stop it.
        String scriptSrc = policy.substring(policy.indexOf("script-src"),
                policy.indexOf(';', policy.indexOf("script-src")));
        assertThat(scriptSrc)
                .as("the exported website's inline bootstrap, hashed")
                .contains("'sha256-");
        assertThat(scriptSrc)
                .as("and no blanket permission for inline script, ever")
                .doesNotContain("'unsafe-inline'");
        assertThat(policy)
                .as("the one concession is for styles, which cannot execute")
                .contains("style-src 'self' 'unsafe-inline'");
        assertThat(policy)
                .as("and nothing external: every relaxation is 'self'")
                .doesNotContain("http://").doesNotContain("https://").doesNotContain("*");
    }

    @Test
    void springSecuritysOwnDefaultsAreStillInForce() throws Exception {
        // Neither chain configures headers() beyond the two above, so these
        // come for free — and asserting them means switching them off cannot
        // pass unnoticed. no-store matters most here: it is a URL with a
        // one-time token in it.
        HttpResponse<String> page = get("/invitations/not-a-real-token");

        assertThat(page.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(page.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(page.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    /* ------------------------------ helpers ----------------------------- */

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(this.base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
