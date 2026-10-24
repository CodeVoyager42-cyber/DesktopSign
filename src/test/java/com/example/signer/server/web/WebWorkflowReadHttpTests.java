package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.workflow.RemoteWorkflowService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E part 1 over real HTTP: what the website may read about workflows,
 * and what it may not.
 *
 * <p>The Phase 3 authorization matrix is deliberately repeated here against
 * the cookie domain. {@code WebWorkflowController} passes the session's
 * username to the very same service the desktop's chain calls, so in
 * principle the rules cannot differ — but "in principle" is how a route ends
 * up on the wrong chain, and the cost of proving otherwise is this file.
 *
 * <p>Uses its own throwaway database, recreated for each run. Skipped when
 * PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.jwt-secret="
                        + WebWorkflowReadHttpTests.SECRET,
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-web-workflow-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebWorkflowReadHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                // Many sign-ins from one address here, and no limit is under test.
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.web.WebWorkflowReadHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class WebWorkflowReadHttpTests {

    static final String DB = "signflow_web_workflow_test";
    static final String SECRET =
            "web-workflow-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static final String PASSWORD = "correct-password-1";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The three routes that take a workflow id. */
    private static final String[] BY_ID = {"", "/audit", "/revisions"};

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WebSessionProperties sessionProperties;

    /** Recreates the throwaway database; false (skipped) when PostgreSQL is unreachable. */
    public static boolean freshDatabase() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DB);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ----------------------------- the tests ---------------------------- */

    @Test
    void eachListHoldsOnlyWhatTheCallerTakesPartIn() throws Exception {
        String owner = account(true);
        String signer = account(true);
        String workflow = workflow(owner, signer);

        // The owner is not a signer of it and the signer did not create it,
        // so each of them sees it in exactly one of the two lists.
        assertThat(read("/owned", login(owner))).contains(workflow);
        assertThat(read("/participating", login(owner))).doesNotContain(workflow);
        assertThat(read("/participating", login(signer))).contains(workflow);
        assertThat(read("/owned", login(signer))).doesNotContain(workflow);
    }

    @Test
    void aStrangerGetsExactlyTheAnswerForAWorkflowThatDoesNotExist() throws Exception {
        String workflow = workflow(account(true), account(true));
        String stranger = login(account(true));
        String missing = "WF-" + (900_000_000 + RANDOM.nextInt(99_999_999));

        for (String route : BY_ID) {
            HttpResponse<String> real = get("/web/api/workflows/" + workflow + route,
                    cookies(stranger), null);
            HttpResponse<String> none = get("/web/api/workflows/" + missing + route,
                    cookies(stranger), null);

            assertThat(real.statusCode()).as("GET " + route).isEqualTo(404);
            assertThat(real.body()).as("GET " + route + " must not differ")
                    .isEqualTo(none.body());
        }
        // And knowing the id does not put it in either of the stranger's lists.
        assertThat(read("/owned", stranger)).doesNotContain(workflow);
        assertThat(read("/participating", stranger)).doesNotContain(workflow);
    }

    @Test
    void withoutASessionEveryRouteIsRefused() throws Exception {
        String workflow = workflow(account(true), account(true));

        List<String> routes = new ArrayList<>(List.of("/owned", "/participating"));
        for (String route : BY_ID) {
            routes.add("/" + workflow + route);
        }
        for (String route : routes) {
            assertThat(get("/web/api/workflows" + route, null, null).statusCode())
                    .as("GET " + route + " without a session").isEqualTo(401);
        }
    }

    @Test
    void aDesktopBearerTokenOpensNothingHere() throws Exception {
        String signer = account(true);
        String workflow = workflow(account(true), signer);
        String bearer = this.jwtService.issueAccessJwt(signer);

        // The very same token, for the very same person, on the chain it
        // belongs to: this is their workflow and the desktop API says so.
        assertThat(get("/api/workflows/" + workflow, null, bearer).statusCode())
                .as("the token is genuine and the person is a signer").isEqualTo(200);

        for (String route : BY_ID) {
            assertThat(get("/web/api/workflows/" + workflow + route, null, bearer)
                    .statusCode())
                    .as("a desktop JWT must never open a website route")
                    .isEqualTo(401);
        }
    }

    @Test
    void anUnverifiedAccountMayStillReadWhereItsTaskStands() throws Exception {
        // The Phase D gate (§5) is on the steps of signing, not on looking:
        // an account that has not confirmed its address can see that it has
        // been asked to sign and how far the document has got. What it cannot
        // do is start a workflow or download the document — part 2 and the
        // filter's business respectively.
        String signer = account(false);
        String workflow = workflow(account(true), signer);
        String session = login(signer);

        assertThat(read("/participating", session)).contains(workflow);
        for (String route : BY_ID) {
            assertThat(get("/web/api/workflows/" + workflow + route, cookies(session), null)
                    .statusCode()).as("GET " + route + " unverified").isEqualTo(200);
        }
    }

    /* ------------------------------ helpers ----------------------------- */

    /** An account with a known password, verified or not as asked. */
    private String account(boolean verified) {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity account = new UserEntity(name, name + "@example.test",
                PasswordHashing.hash(PASSWORD), name, AccountStatus.ACTIVE);
        if (verified) {
            account.setEmailVerifiedAt(Instant.now());
        }
        this.users.save(account);
        return name;
    }

    /** A workflow owned by {@code owner}, with one slot per signer. */
    private String workflow(String owner, String... signers) {
        List<CreateWorkflowRequest.SignerSpec> slots = new ArrayList<>();
        for (int i = 0; i < signers.length; i++) {
            slots.add(new CreateWorkflowRequest.SignerSpec(i + 1, signers[i], fingerprint()));
        }
        return this.workflows.createWorkflow(
                new CreateWorkflowRequest("phase-e.pdf", "SEQUENTIAL", slots),
                pdf(), owner).workflowId();
    }

    /** The body of one list route, read with a session. */
    private String read(String route, String session) throws Exception {
        HttpResponse<String> response =
                get("/web/api/workflows" + route, cookies(session), null);
        assertThat(response.statusCode()).as("GET " + route).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> get(String path, String cookieHeader, String bearer)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + this.port + path)).GET();
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return this.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Signs in and returns the session cookie value. */
    private String login(String username) throws Exception {
        HttpResponse<String> bootstrap = get("/web/api/csrf", null, null);
        assertThat(bootstrap.statusCode()).isEqualTo(204);
        String token = cookie(bootstrap, CSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie"));
        Optional<String> bootstrapSession = cookie(bootstrap, sessionCookie());

        String header = CSRF_COOKIE + "=" + token
                + bootstrapSession.map(s -> "; " + sessionCookie() + "=" + s).orElse("");
        HttpResponse<String> response = this.http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + this.port + "/web/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", header)
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"" + username + "\",\"password\":\""
                                        + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return cookie(response, sessionCookie())
                .orElseThrow(() -> new AssertionError("no session cookie"));
    }

    /** Whatever the session cookie is called in this configuration. */
    private String sessionCookie() {
        return this.sessionProperties.getCookieName();
    }

    private String cookies(String session) {
        return sessionCookie() + "=" + session;
    }

    private static Optional<String> cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .map(h -> h.substring(name.length() + 1).split(";", 2)[0])
                .filter(v -> !v.isEmpty())
                .findFirst();
    }

    private static String fingerprint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static byte[] pdf() {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
