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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            assertThat(page.body()).as(path).contains("<!DOCTYPE html>");
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
            assertThat(page.body()).as(path).contains("<!DOCTYPE html>");
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
    void everyInlineScriptIsPermittedByItsOwnHashAndNothingElseIs() throws Exception {
        // The invariant that replaced "there is no inline script at all".
        //
        // The website is a Next.js static export, and every page carries the
        // inline payload React needs to take over the markup. The policy is
        // still script-src 'self' with no 'unsafe-inline', so each of those
        // blocks is permitted one at a time by the hash of its contents
        // (InlineScriptHashes). Get that wrong and the page loads its code and
        // then does nothing — in a browser console, and nowhere a build would
        // notice. So this computes the hashes the way a browser does and
        // checks the header really names them.
        for (String path : new String[] {"/", "/sign-in", "/documents"}) {
            HttpResponse<String> page = get(path);
            String policy = page.headers()
                    .firstValue("Content-Security-Policy").orElseThrow();

            Matcher inline = Pattern.compile(
                    "<script(?![^>]*\\ssrc=)([^>]*)>(.*?)</script>", Pattern.DOTALL)
                    .matcher(page.body());
            int found = 0;
            while (inline.find()) {
                if (inline.group(2).isEmpty()
                        || inline.group(1).contains("application/json")) {
                    continue;
                }
                found++;
                String hash = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(inline.group(2).getBytes(StandardCharsets.UTF_8)));
                assertThat(policy)
                        .as(path + " has an inline script the policy does not permit")
                        .contains("'sha256-" + hash + "'");
            }
            assertThat(found)
                    .as(path + " should have inline scripts to hash — if the export"
                            + " stops emitting them, this test has stopped testing"
                            + " anything and the hashes can go")
                    .isPositive();
            // Only the script-src segment: 'unsafe-inline' appears later in
            // the policy for styles, which is a concession of its own and
            // deliberately not this test's business.
            String scriptSrc = policy.substring(policy.indexOf("script-src"),
                    policy.indexOf(';', policy.indexOf("script-src")));
            assertThat(scriptSrc)
                    .as("still no blanket permission for inline script")
                    .startsWith("script-src 'self' 'sha256-")
                    .doesNotContain("'unsafe-inline'");
        }
    }

    @Test
    void thePagesLoadTheirCodeFromThisOriginOnly() throws Exception {
        // Everything the export references is content-hashed under /_next,
        // which is this server's own path. An absolute URL in the markup would
        // mean the build had been pointed at a CDN — blocked by the policy at
        // runtime, and only visible in a browser console.
        for (String path : new String[] {"/", "/sign-in", "/documents", "/account"}) {
            String html = get(path).body();

            assertThat(html).as(path).contains("/_next/static/");
            assertThat(html)
                    .as(path + " must load nothing from another origin")
                    .doesNotContain("http://")
                    .doesNotContain("https://");
            assertThat(html)
                    .as(path + " must carry no inline event handler: a hash covers"
                            + " a <script> block, and nothing covers onclick=")
                    .doesNotContain("onclick=")
                    .doesNotContain("onload=");
        }
    }

    @Test
    void aHeadRequestIsAnsweredAndNotRefused() throws Exception {
        // Found by running the server and asking it for HEAD /assets/app.js:
        // it answered 401, because a matcher restricted to GET sends
        // everything else to denyAll(). Browsers, proxies, link checkers and
        // uptime monitors all use HEAD, and a HEAD of a public page reveals
        // exactly what a GET of it does.
        for (String path : new String[] {"/", "/index.html", anyScript(),
                anyStylesheet(), "/sign-in", "/documents"}) {
            HttpResponse<Void> head = http.send(
                    HttpRequest.newBuilder(URI.create(this.base + path))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(head.statusCode()).as("HEAD " + path).isEqualTo(200);
        }
    }

    @Test
    void everyPageTheWebsiteListsIsItsOwnDocument() throws Exception {
        // Each of these is one line in FrontendRoutingConfiguration.PAGES,
        // which SecurityConfig.PUBLIC reads as well — so a page cannot be
        // routable but refused, nor permitted and never routed.
        //
        // They are separate documents now. The old website was one
        // index.html that built every view in the browser; the export writes
        // a real page per route, so the forward goes to that page's own file
        // and this asserts the right one arrives.
        for (String path : new String[] {"/sign-in", "/sign-up", "/forgot-password",
                "/change-password", "/account", "/documents", "/document",
                "/send", "/verify-email", "/reset-password"}) {
            HttpResponse<String> page = get(path);

            assertThat(page.statusCode()).as(path).isEqualTo(200);
            assertThat(page.body()).as(path).contains("<!DOCTYPE html>");
            // "app<path>/page" and not just "app<path>": /document is a
            // prefix of /documents, so the looser form would be satisfied by
            // the wrong page's chunk — the one mistake this assertion exists
            // to catch.
            assertThat(page.body())
                    .as(path + " should be the page for that route")
                    .contains("/_next/static/chunks/app" + path + "/page");
        }
    }

    @Test
    void theScriptAndStylesheetAreServedWithTypesABrowserWillAccept()
            throws Exception {
        // A script served as text/plain is refused by the browser outright,
        // and a stylesheet served as the wrong type is ignored. Both failures
        // appear only in a browser console, so they are worth pinning here.
        // The file names are content-hashed by the build, so they are read out
        // of the page rather than written down.
        assertThat(get(anyScript()).headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).contains("javascript"));
        assertThat(get(anyStylesheet()).headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).contains("text/css"));
    }

    @Test
    void theFontIsServedFromHereAndNotFromAFontService() throws Exception {
        // font-src 'self' permits no font service, which is the point. The
        // hand-written stylesheet pointed @font-face at files nobody had
        // added and fell back to the platform font; the build now emits the
        // font itself, so this asserts there is really one to serve.
        String css = get(anyStylesheet()).body();
        Matcher font = Pattern.compile("/_next/static/media/[^\")]+\\.woff2")
                .matcher(css);

        assertThat(font.find()).as("the stylesheet should reference a woff2").isTrue();
        HttpResponse<String> served = get(font.group());
        assertThat(served.statusCode()).as(font.group()).isEqualTo(200);
        assertThat(served.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).contains("font"));
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
        assertThat(api.body()).doesNotContain("<!DOCTYPE html>");
    }

    @Test
    void aMissingAssetIs404AndNotTheEntryPage() throws Exception {
        // Same reasoning: /_next/** is served, so a file that is not there
        // must say so rather than returning HTML that a script tag would then
        // try to execute as JavaScript.
        HttpResponse<String> missing = get("/_next/static/chunks/does-not-exist.js");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain("<!DOCTYPE html>");
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

    /**
     * One script the entry page loads. The build gives every file a
     * content-hashed name, so a test that wrote one down would be rewritten
     * by the next frontend change; this reads one out of the page instead.
     */
    private String anyScript() throws Exception {
        return firstMatch("/_next/static/chunks/[^\"]+\\.js", get("/").body(), "script");
    }

    private String anyStylesheet() throws Exception {
        return firstMatch("/_next/static/css/[^\"]+\\.css", get("/").body(), "stylesheet");
    }

    private static String firstMatch(String regex, String html, String what) {
        Matcher match = Pattern.compile(regex).matcher(html);
        assertThat(match.find())
                .as("the entry page should reference a " + what)
                .isTrue();
        return match.group();
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
