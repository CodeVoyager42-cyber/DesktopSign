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

    /** The policy both chains send. Restated here because this is its spec. */
    private static final String POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'none';"
                    + " base-uri 'none'; frame-ancestors 'none'";

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
                .contains(POLICY);
    }

    @Test
    void theJsonApiSendsThemToo() throws Exception {
        // Not because a JSON response can load anything, but because a
        // header that is only right on the routes someone remembered to list
        // will be wrong on the next route added.
        HttpResponse<String> health = get("/api/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(health.headers().firstValue("Content-Security-Policy")).contains(POLICY);
    }

    @Test
    void anUnauthenticatedRefusalCarriesThemAsWell() throws Exception {
        // The 401 path goes through JsonSecurityErrors rather than a
        // controller, so it is worth checking that it has not bypassed the
        // header writer.
        HttpResponse<String> refused = get("/api/workflows/my-tasks");

        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(refused.headers().firstValue("Content-Security-Policy")).contains(POLICY);
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
                .as("no script-src directive exists to loosen, and no eval")
                .doesNotContain("script-src").doesNotContain("unsafe-eval");
        assertThat(policy)
                .as("the one concession is for styles, which cannot execute")
                .contains("style-src 'unsafe-inline'");
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
