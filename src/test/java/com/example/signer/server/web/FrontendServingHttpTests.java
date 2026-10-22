package com.example.signer.server.web;

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
 * The website served from this application's own origin (security Phase D1
 * risk D2): the entry point, the two pages the account e-mails link to, and —
 * the half that matters more — that opening them opened nothing else.
 *
 * <p>Both filter chains end in {@code denyAll()}, so every path that answers
 * at all is one somebody listed. These tests are the record of which those
 * are.
 */
@SpringBootTest(classes = com.example.signer.server.WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
class FrontendServingHttpTests {

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("frontend-serving-store").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private String base;

    @LocalServerPort
    void capturePort(int port) {
        this.base = "http://localhost:" + port;
    }

    /* ---------------------------- what it serves ------------------------ */

    @Test
    void theEntryPointIsServedFromTheRootOfThisOrigin() throws Exception {
        for (String path : new String[] {"/", "/index.html"}) {
            HttpResponse<String> page = get(path);

            assertThat(page.statusCode()).as(path).isEqualTo(200);
            assertThat(page.headers().firstValue("Content-Type"))
                    .as(path)
                    .hasValueSatisfying(type -> assertThat(type).contains("text/html"));
            assertThat(page.body()).as(path).contains("<!doctype html>");
        }
    }

    @Test
    void theTwoPagesTheAccountEmailsLinkToAnswerWithIt() throws Exception {
        // Before this they led nowhere: the e-mails have always carried these
        // URLs, and nothing served them. The forward is a named route rather
        // than a catch-all, so this test is also what proves the route exists.
        for (String path : new String[] {
                "/verify-email?token=" + "a".repeat(64),
                "/reset-password?token=" + "b".repeat(64)}) {
            HttpResponse<String> page = get(path);

            assertThat(page.statusCode()).as(path).isEqualTo(200);
            assertThat(page.body()).as(path).contains("<!doctype html>");
        }
    }

    @Test
    void thePageDoesNotEchoTheTokenBackIntoItsOwnHtml() throws Exception {
        // It stays in the address bar, where the page's own script can read
        // it. Writing it into the document would put it in the DOM for any
        // injected script to find, and into anything that caches the HTML.
        String token = "c".repeat(64);

        String html = get("/reset-password?token=" + token).body();

        assertThat(html).doesNotContain(token);
    }

    @Test
    void theTokenPagesCarryTheNoReferrerHeaderToo() throws Exception {
        // Risk L9 again, and now it is load-bearing for these pages as well:
        // the token is in the URL, and the page will fetch from the API.
        HttpResponse<String> page = get("/reset-password?token=" + "d".repeat(64));

        assertThat(page.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(page.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(policy -> assertThat(policy)
                        .contains("default-src 'none'")
                        .contains("script-src 'self'")
                        .doesNotContain("script-src 'self' 'unsafe-inline'"));
    }

    @Test
    void theEntryPointItselfNeedsNothingThePolicyForbids() throws Exception {
        // The placeholder — and whatever replaces it — has to live inside the
        // policy. Nothing external, and no inline script however convenient.
        String html = get("/").body();

        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("http://").doesNotContain("https://");
    }

    /* -------------------------- what it did not open -------------------- */

    @Test
    void nothingElseBecameReadable() throws Exception {
        // The point of listing routes one by one instead of permitting a
        // wildcard. A path nobody listed is still refused by denyAll(), which
        // for an anonymous caller means 401 — Spring Security sends them to
        // authenticate rather than saying the path is forbidden.
        for (String path : new String[] {"/application.yml", "/anything-at-all",
                "/web/anything"}) {
            HttpResponse<String> refused = get(path);
            assertThat(refused.statusCode()).as(path).isIn(401, 403, 404);
            assertThat(refused.body()).as(path).doesNotContain("jwt-secret");
        }
    }

    @Test
    void andAuthenticatingDoesNotMakeThemReadableEither() throws Exception {
        // The 401 above could be read as "sign in and it is yours". It is
        // not: denyAll() denies whoever asks, so a valid token turns the same
        // request into a 403 rather than a file.
        String token = signIn();

        HttpResponse<String> config = http.send(
                HttpRequest.newBuilder(URI.create(this.base + "/application.yml"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(config.statusCode()).isEqualTo(403);
        assertThat(config.body())
                .as("and certainly not the configuration file")
                .doesNotContain("jwt-secret").doesNotContain("database");
    }

    @Test
    void theApiStillRequiresATokenAndStillAnswersInJson() throws Exception {
        // A catch-all SPA forward would have answered this with the HTML page
        // and a 200, which is why the routing is a list.
        HttpResponse<String> api = get("/api/workflows/my-tasks");

        assertThat(api.statusCode()).isEqualTo(401);
        assertThat(api.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).contains("application/json"));
        assertThat(api.body()).doesNotContain("<!doctype html>");
    }

    @Test
    void aMissingAssetIs404AndNotTheEntryPage() throws Exception {
        // Same reasoning: /assets/** is served, so a file that is not there
        // must say so rather than returning HTML that a script tag would then
        // try to execute as JavaScript.
        HttpResponse<String> missing = get("/assets/does-not-exist.js");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain("<!doctype html>");
    }

    @Test
    void thereIsNoCorsHeaderBecauseThereIsNoSecondOrigin() throws Exception {
        // The same-origin decision, made visible. If a second origin ever
        // needs the API, this test is where that change announces itself —
        // along with the SameSite and __Host- consequences for the cookie.
        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(URI.create(this.base + "/web/api/csrf"))
                        .header("Origin", "https://elsewhere.example")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(page.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(page.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    /* ------------------------------ helpers ----------------------------- */

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(this.base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A real access token, for showing that denial is not about the token. */
    private String signIn() throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"alice\",\"password\":\"alice-pass\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("sign-in for the fixture").isEqualTo(200);
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(login.body()).path("accessToken").asText();
    }
}
