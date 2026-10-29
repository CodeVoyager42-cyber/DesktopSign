package com.example.signer.server.launch;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.server.workflow.RemoteWorkflowService;
import com.example.signer.server.security.RateLimitTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B: website launch codes over real HTTP, database mode. {@code alice}
 * owns the workflows; {@code bob} and {@code charlie} sign or are strangers.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-launch",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.login.rate-limit=1000",
                "signature.remote-server.desktop.min-version=1.2.0",
                // As in production (the test resources enable the dev profile).
                "signature.remote-server.desktop.accept-unknown-version=false"
        })
@ActiveProfiles("server")
@ExtendWith(OutputCaptureExtension.class)
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class LaunchCodeHttpTests {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String APP = "1.2.0";

    @LocalServerPort
    private int port;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void ageEarlierCodes() {
        jdbc = new JdbcTemplate(dataSource);
        // D3: the per-address counts are shared and persistent now, so an
        // earlier class could have spent this address's budget.
        RateLimitTestSupport.clear(dataSource);
        // Only the per-minute issue throttle looks at created_at: move codes
        // from earlier tests out of that window (test database only).
        jdbc.update("UPDATE launch_codes SET created_at = created_at - interval '5 minutes'"
                + " WHERE created_at > now() - interval '5 minutes'");
    }

    /* ------------------------------ helpers ----------------------------- */

    private record Reply(int status, String body, HttpResponse<String> raw) {
        JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                throw new AssertionError("not JSON: " + body, e);
            }
        }

        String code() {
            return json().path("code").asText(null);
        }
    }

    private Reply call(String method, String path, String bearer, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (json != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), r);
    }

    private String login(String user) throws Exception {
        Reply r = call("POST", "/api/auth/login", null,
                "{\"username\":\"" + user + "\",\"password\":\"" + user + "-pass\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json().path("accessToken").asText();
    }

    private static String fp() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().withUpperCase().formatHex(b);
    }

    private static byte[] pdf() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** alice's sequential workflow with the given slots (userId, fingerprint-or-null). */
    private String workflow(String... slots) {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        for (int i = 0; i < slots.length; i += 2) {
            specs.add(new CreateWorkflowRequest.SignerSpec(i / 2 + 1, slots[i], slots[i + 1]));
        }
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("launch.pdf", "SEQUENTIAL", specs), "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), "alice");
        if (!prep.pendingInvites().isEmpty()) {
            coordinator.invite(view.workflowId(), prep.pendingInvites(), "alice");
        }
        return view.workflowId();
    }

    /** Issues a code as {@code bearer}; returns the bare code. */
    private String issue(String wf, String bearer) throws Exception {
        Reply r = call("POST", "/api/workflows/" + wf + "/launch-codes", bearer, null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        String link = r.json().path("deepLink").asText();
        assertThat(link).matches("^signflow://launch\\?v=1&c=[A-Za-z0-9_-]{43}$");
        return link.substring(link.indexOf("&c=") + 3);
    }

    private Reply exchange(String code, String version) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "code", code, "appVersion", version, "device", "TEST-PC (Linux)"));
        return call("POST", "/api/desktop/launch", null, body);
    }

    /* ------------------------------- tests ------------------------------ */

    @Test
    void aCodeOpensASessionLimitedToThatOneSigningTask() throws Exception {
        String wf = workflow("bob", fp());
        String other = workflow("bob", fp());
        String bob = login("bob");
        Reply issued = call("POST", "/api/workflows/" + wf + "/launch-codes", bob, null);
        assertThat(issued.status()).isEqualTo(201);
        assertThat(issued.raw().headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(issued.json().path("expiresInSeconds").asLong()).isEqualTo(120);
        String code = issued.json().path("deepLink").asText().replaceAll(".*&c=", "");
        // Only the hash is stored.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM launch_codes WHERE code_hash = ?",
                Long.class, LaunchCodeService.hash(code))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM launch_codes WHERE code_hash = ?",
                Long.class, code)).isZero();

        Reply opened = exchange(code, APP);
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        JsonNode s = opened.json();
        assertThat(s.path("workflowId").asText()).isEqualTo(wf);
        assertThat(s.path("signerId").asText()).isEqualTo("bob");
        assertThat(s.path("signerOrder").asInt()).isEqualTo(1);
        assertThat(s.path("expiresIn").asLong()).isBetween(60L, 3600L);
        assertThat(s.has("refreshToken")).as("no refresh token").isFalse();
        assertThat(opened.body()).doesNotContainIgnoringCase("refresh");
        String task = s.path("accessToken").asText();

        // Inside the task: allowed.
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .isEqualTo(200);
        // Everything else: refused, even bob's other workflow.
        assertThat(call("GET", "/api/workflows/" + other + "/task", task, null).status())
                .isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + wf, task, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/my-tasks", task, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/workflows/" + wf + "/audit", task, null).status())
                .isEqualTo(403);
        assertThat(call("POST", "/api/workflows/" + wf + "/launch-codes", task, null).status())
                .isEqualTo(403);
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/charlie/certificate", task,
                "{\"certificateSha256\":\"" + fp() + "\"}").status()).isEqualTo(403);
        assertThat(call("GET", "/api/desktop/launches", task, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/auth/me", task, null).status()).isIn(401, 403);
        assertThat(call("POST", "/api/auth/refresh", null,
                "{\"refreshToken\":\"" + task + "\"}").status()).isIn(400, 401);

        // The website can show where it was opened, and the audit says so.
        Reply recent = call("GET", "/api/desktop/launches", bob, null);
        assertThat(recent.status()).isEqualTo(200);
        assertThat(recent.body()).contains("\"OPENED\"").contains("TEST-PC (Linux)")
                .doesNotContain(code).doesNotContain(LaunchCodeService.hash(code));
        assertThat(call("GET", "/api/workflows/" + wf + "/audit", bob, null).body())
                .contains("Desktop signing session opened");
    }

    @Test
    void aCodeWorksOnceAndEveryRefusalLooksTheSame() throws Exception {
        String wf = workflow("bob", fp());
        String bob = login("bob");
        String used = issue(wf, bob);
        assertThat(exchange(used, APP).status()).isEqualTo(200);

        String expired = issue(wf, bob);
        jdbc.update("UPDATE launch_codes SET created_at = now() - interval '3 minutes',"
                + " expires_at = now() - interval '1 minute' WHERE code_hash = ?",
                LaunchCodeService.hash(expired));

        String superseded = issue(wf, bob);
        issue(wf, bob); // a newer code for the same slot revokes the unused one

        byte[] unknownBytes = new byte[32];
        RANDOM.nextBytes(unknownBytes);
        String unknown = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(unknownBytes);

        List<Reply> refusals = List.of(exchange(used, APP), exchange(expired, APP),
                exchange(superseded, APP), exchange(unknown, APP), exchange("short", APP),
                exchange(used + "x", APP));
        for (Reply r : refusals) {
            assertThat(r.status()).as(r.body()).isEqualTo(400);
            assertThat(r.code()).isEqualTo("LAUNCH_CODE_INVALID");
            assertThat(r.body()).isEqualTo(refusals.get(0).body());
        }
    }

    @Test
    void underConcurrentExchangeExactlyOneWins() throws Exception {
        String wf = workflow("bob", fp());
        String code = issue(wf, login("bob"));
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                Callable<Integer> c = () -> {
                    go.await();
                    return exchange(code, APP).status();
                };
                results.add(pool.submit(c));
            }
            go.countDown();
            int ok = 0;
            int refused = 0;
            for (Future<Integer> f : results) {
                int st = f.get();
                if (st == 200) {
                    ok++;
                } else if (st == 400) {
                    refused++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(refused).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anOutdatedDesktopIsToldToUpgradeWithoutSpendingTheCode() throws Exception {
        String wf = workflow("bob", fp());
        String code = issue(wf, login("bob"));
        for (String old : new String[] {"1.1.9", "0.9.0", "garbage", ""}) {
            Reply r = exchange(code, old);
            assertThat(r.status()).as(old).isEqualTo(426);
            assertThat(r.code()).isEqualTo("UPGRADE_REQUIRED");
            assertThat(r.body()).contains("1.2.0");
        }
        // Missing version, and an unknown code with an old version: same 426.
        assertThat(call("POST", "/api/desktop/launch", null, "{\"code\":\"" + code + "\"}")
                .status()).isEqualTo(426);
        assertThat(exchange("x".repeat(43), "0.1.0").status()).isEqualTo(426);
        // The code was never looked at: an up-to-date desktop can still use it.
        assertThat(exchange(code, "1.2.0-beta").status()).isEqualTo(426);
        assertThat(exchange(code, "1.10.0").status()).isEqualTo(200);
    }

    @Test
    void aDesktopOfUnknownVersionIsRefusedOutsideDevelopment() throws Exception {
        String wf = workflow("bob", fp());
        String code = issue(wf, login("bob"));
        Reply r = exchange(code, "unknown");
        assertThat(r.status()).isEqualTo(426);
        assertThat(r.code()).isEqualTo("UPGRADE_REQUIRED");
        assertThat(r.body()).contains("does not report its version");
        // Refused before the code was looked at: still usable.
        assertThat(exchange(code, APP).status()).isEqualTo(200);
    }

    @Test
    void onlyTheSignerWhoseTurnItIsGetsACode() throws Exception {
        String wf = workflow("bob", fp(), "charlie", fp());
        String missing = "WF-" + (900_000_000 + RANDOM.nextInt(99_999_999));
        String alice = login("alice");
        String charlie = login("charlie");
        // Stranger to this workflow: exactly the "does not exist" answer.
        String only = workflow("bob", fp());
        Reply stranger = call("POST", "/api/workflows/" + only + "/launch-codes", charlie, null);
        Reply none = call("POST", "/api/workflows/" + missing + "/launch-codes", charlie, null);
        assertThat(stranger.status()).isEqualTo(404);
        assertThat(stranger.body()).isEqualTo(none.body());
        // Owner who is not a signer.
        Reply owner = call("POST", "/api/workflows/" + wf + "/launch-codes", alice, null);
        assertThat(owner.status()).isEqualTo(403);
        // Signer whose turn it is not (sequential, slot 2).
        Reply early = call("POST", "/api/workflows/" + wf + "/launch-codes", charlie, null);
        assertThat(early.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM launch_codes l JOIN workflows w"
                + " ON w.id = l.workflow_id WHERE w.workflow_id = ? AND l.username = 'charlie'",
                Long.class, wf)).isZero();
    }

    @Test
    void aCodeStopsWorkingWhenTheTaskChangesBeforeItIsUsed() throws Exception {
        String wf = workflow("bob", fp());
        String code = issue(wf, login("bob"));
        assertThat(call("POST", "/api/workflows/" + wf + "/cancel", login("alice"), null)
                .status()).isIn(200, 204);
        Reply r = exchange(code, APP);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("LAUNCH_CODE_INVALID");
        // And it is spent, not left for a later try.
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM launch_codes"
                + " WHERE code_hash = ?", Boolean.class, LaunchCodeService.hash(code))).isTrue();
    }

    @Test
    void revokingALaunchEndsTheDesktopSession() throws Exception {
        String wf = workflow("bob", fp());
        String bob = login("bob");
        String task = exchange(issue(wf, bob), APP).json().path("accessToken").asText();
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .isEqualTo(200);
        long id = call("GET", "/api/desktop/launches", bob, null).json().get(0).path("id").asLong();
        // Someone else's launch: the same answer as an unknown one.
        Reply foreign = call("POST", "/api/desktop/launches/" + id + "/revoke", login("charlie"), null);
        Reply unknown = call("POST", "/api/desktop/launches/999999999/revoke", login("charlie"), null);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.body()).isEqualTo(unknown.body());
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .isEqualTo(200);

        assertThat(call("POST", "/api/desktop/launches/" + id + "/revoke", bob, null).status())
                .isEqualTo(204);
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .isEqualTo(401);
        assertThat(call("GET", "/api/workflows/" + wf + "/audit", bob, null).body())
                .contains("revoked by its owner");
    }

    @Test
    void anUnverifiedAddressCannotStartOrFinishALaunch() throws Exception {
        String wf = workflow("charlie", fp());
        String charlie = login("charlie");
        String code = issue(wf, charlie);
        try {
            jdbc.update("UPDATE users SET email_verified_at = NULL WHERE username = 'charlie'");
            Reply r = call("POST", "/api/workflows/" + wf + "/launch-codes", charlie, null);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("EMAIL_NOT_VERIFIED");
            assertThat(exchange(code, APP).status()).isEqualTo(400);
        } finally {
            jdbc.update("UPDATE users SET email_verified_at = now() WHERE username = 'charlie'");
        }
    }

    @Test
    void issuingIsThrottledPerUser() throws Exception {
        String wf = workflow("bob", fp());
        String bob = login("bob");
        for (int i = 0; i < LaunchCodeService.MAX_ISSUED_PER_MINUTE; i++) {
            issue(wf, bob);
        }
        Reply r = call("POST", "/api/workflows/" + wf + "/launch-codes", bob, null);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void racingIssueRequestsCannotExceedThePerMinuteLimit() throws Exception {
        String wf = workflow("bob", fp());
        String bob = login("bob");
        int n = 14;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return call("POST", "/api/workflows/" + wf + "/launch-codes", bob, null)
                            .status();
                }));
            }
            go.countDown();
            int issued = 0;
            int limited = 0;
            for (Future<Integer> f : results) {
                int st = f.get();
                if (st == 201) {
                    issued++;
                } else if (st == 429) {
                    limited++;
                }
            }
            assertThat(issued).isEqualTo(LaunchCodeService.MAX_ISSUED_PER_MINUTE);
            assertThat(limited).isEqualTo(n - LaunchCodeService.MAX_ISSUED_PER_MINUTE);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void codesAndTokensNeverReachTheLog(CapturedOutput output) throws Exception {
        String wf = workflow("bob", fp());
        String code = issue(wf, login("bob"));
        Reply opened = exchange(code, APP);
        exchange(code, APP);
        String token = opened.json().path("accessToken").asText();
        assertThat(output.getAll()).contains("Launch code issued")
                .doesNotContain(code).doesNotContain(LaunchCodeService.hash(code))
                .doesNotContain(token);
    }

    @Test
    void theDesktopClientOpensTheTaskWithTheCode() throws Exception {
        String base = "http://localhost:" + port;
        String wf = workflow("bob", fp());
        String code = issue(wf, login("bob"));
        assertThat(com.example.signer.client.WorkflowApiClient.health(base).status())
                .isEqualTo("UP");
        var session = com.example.signer.client.WorkflowApiClient.exchangeLaunchCode(base, code,
                "1.3.0", "Desktop test");
        var client = com.example.signer.client.WorkflowApiClient.forTask(base, session);
        try {
            assertThat(client.task(wf)).isNotNull();
        } finally {
            client.close();
        }
        // Revoked from the website: the desktop says where to start again.
        long id = call("GET", "/api/desktop/launches", login("bob"), null).json().get(0)
                .path("id").asLong();
        call("POST", "/api/desktop/launches/" + id + "/revoke", login("bob"), null);
        var revoked = com.example.signer.client.WorkflowApiClient.forTask(base, session);
        try {
            revoked.task(wf);
            throw new AssertionError("revoked session still works");
        } catch (com.example.signer.exception.SignerException e) {
            assertThat(e.getMessage()).contains("website").doesNotContain("invitation");
        } finally {
            revoked.close();
        }
        // A second desktop with the same link: refused, in words for the dialog.
        try {
            com.example.signer.client.WorkflowApiClient.exchangeLaunchCode(base, code, "1.3.0", null);
            throw new AssertionError("replay accepted");
        } catch (com.example.signer.client.RemoteApiException e) {
            assertThat(e.serverCode()).isEqualTo("LAUNCH_CODE_INVALID");
            assertThat(e.getMessage()).doesNotContain(code);
        }
    }

    @Test
    void theHealthCheckIsPublicAndSaysNothingSensitive() throws Exception {
        Reply r = call("GET", "/api/health", null, null);
        assertThat(r.status()).isEqualTo(200);
        List<String> fields = new ArrayList<>();
        r.json().fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("status", "apiVersion", "minDesktopVersion");
        assertThat(r.json().path("status").asText()).isEqualTo("UP");
        assertThat(r.json().path("minDesktopVersion").asText()).isEqualTo("1.2.0");
        // The old probe — logging in as probe/probe — is not needed and still fails.
        assertThat(call("POST", "/api/auth/login", null,
                "{\"username\":\"probe\",\"password\":\"probe\"}").status()).isEqualTo(401);
    }
}
