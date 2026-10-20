package com.example.signer.server.launch;

import com.example.signer.client.RemoteSigningService;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.server.workflow.RemoteWorkflowService;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.SignatureStatus;
import com.example.signer.workflow.WorkflowSigningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B remediation (review finding H2): a desktop task session opened
 * with a launch code can sign its own, already-prepared slot and do nothing
 * else — in particular it can never bind or replace a certificate, so a
 * stolen launch code cannot attach another card to the signer's slot.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-task-scope",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class TaskSessionScopeHttpTests {

    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    private int port;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        // Keep the per-user issue limit out of the way (test database only).
        jdbc.update("UPDATE launch_codes SET created_at = created_at - interval '5 minutes',"
                + " expires_at = expires_at - interval '5 minutes'"
                + " WHERE created_at > now() - interval '5 minutes'");
    }

    @AfterEach
    void tearDown() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    /* ------------------------------ helpers ----------------------------- */

    private record Reply(int status, String body) {
        String code() {
            try {
                return new ObjectMapper().readTree(body).path("code").asText(null);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private Reply call(String method, String path, String bearer, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
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
        return new Reply(r.statusCode(), r.body());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String login(String user) throws Exception {
        Reply r = call("POST", "/api/auth/login", null,
                "{\"username\":\"" + user + "\",\"password\":\"" + user + "-pass\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("accessToken").asText();
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

    private String workflow(String... slots) {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        for (int i = 0; i < slots.length; i += 2) {
            specs.add(new CreateWorkflowRequest.SignerSpec(i / 2 + 1, slots[i], slots[i + 1]));
        }
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("scope.pdf", "SEQUENTIAL", specs), "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), "alice");
        return view.workflowId();
    }

    /** Opens a task session for {@code user} on {@code wf}; returns the token. */
    private String taskSession(String wf, String user) throws Exception {
        Reply issued = call("POST", "/api/workflows/" + wf + "/launch-codes", login(user), null);
        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        String link = mapper.readTree(issued.body()).path("deepLink").asText();
        String code = link.substring(link.indexOf("&c=") + 3);
        Reply opened = call("POST", "/api/desktop/launch", null, mapper.writeValueAsString(
                Map.of("code", code, "appVersion", "9.9.9", "device", "scope-test")));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        return mapper.readTree(opened.body()).path("accessToken").asText();
    }

    /** Everything a refused request could have changed, straight from the database. */
    private List<Map<String, Object>> snapshot(String wf) {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList(
                "SELECT status, claimed_turn_username FROM workflows WHERE workflow_id = ?", wf));
        rows.addAll(jdbc.queryForList("SELECT s.signer_order, s.username, s.certificate_sha256,"
                + " s.bound_at, s.signed FROM workflow_signers s JOIN workflows w"
                + " ON w.id = s.workflow_id WHERE w.workflow_id = ? ORDER BY s.signer_order", wf));
        rows.addAll(jdbc.queryForList("SELECT count(*) AS audit FROM audit_events a JOIN workflows w"
                + " ON w.id = a.workflow_id WHERE w.workflow_id = ?", wf));
        rows.addAll(jdbc.queryForList("SELECT count(*) AS revisions FROM document_versions d"
                + " JOIN workflows w ON w.id = d.workflow_id WHERE w.workflow_id = ?", wf));
        return rows;
    }

    private String mockFingerprint() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        String fp = tokenService.getCertificates().stream()
                .filter(c -> MockSigningToken.DEFAULT_ALIAS.equals(c.getAlias()))
                .map(SigningCertificate::getSha256Fingerprint)
                .findFirst().orElseThrow();
        tokenService.disconnect();
        simulator.removeToken();
        return fp;
    }

    /* ------------------------------- tests ------------------------------ */

    @Test
    void aTaskTokenCannotBindOrReplaceACertificateAndChangesNothing() throws Exception {
        String original = fp();
        String wf = workflow("bob", original);
        String task = taskSession(wf, "bob");
        var before = snapshot(wf);

        for (String who : new String[] {"bob", "charlie", "alice"}) {
            Reply r = call("POST", "/api/workflows/" + wf + "/signers/" + who + "/certificate",
                    task, "{\"certificateSha256\":\"" + fp() + "\"}");
            assertThat(r.status()).as(who).isEqualTo(403);
            assertThat(r.code()).isEqualTo("WORKFLOW_NOT_ACCESSIBLE");
        }
        assertThat(snapshot(wf)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT s.certificate_sha256 FROM workflow_signers s"
                + " JOIN workflows w ON w.id = s.workflow_id WHERE w.workflow_id = ?",
                String.class, wf)).isEqualToIgnoringCase(original);
        // The session itself still works for its real purpose.
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .isEqualTo(200);
    }

    @Test
    void aTaskTokenReachesNoOtherWorkflowSlotOrOperation() throws Exception {
        String wf = workflow("bob", fp(), "charlie", fp());
        String other = workflow("bob", fp());
        String task = taskSession(wf, "bob");
        var before = snapshot(wf);
        var otherBefore = snapshot(other);

        // Another workflow where bob is the signer whose turn it is.
        for (String[] r : new String[][] {{"GET", "/task"}, {"GET", "/document"},
                {"POST", "/claim"}, {"POST", "/signatures"}}) {
            assertThat(call(r[0], "/api/workflows/" + other + r[1], task, null).status())
                    .as(r[0] + " other" + r[1]).isEqualTo(403);
        }
        // Everything in its own workflow that is not signing its own slot.
        String[][] refused = {{"GET", ""}, {"GET", "/audit"}, {"GET", "/revisions"},
                {"GET", "/eligible-signers"}, {"GET", "/invitations"}, {"POST", "/invitations"},
                {"POST", "/cancel"}, {"POST", "/launch-codes"},
                {"POST", "/signers/charlie/certificate"}, {"POST", "/signers/bob/certificate"}};
        for (String[] r : refused) {
            assertThat(call(r[0], "/api/workflows/" + wf + r[1], task, "{}").status())
                    .as(r[0] + " " + r[1]).isEqualTo(403);
        }
        for (String path : new String[] {"/api/workflows/my-tasks", "/api/workflows/owned",
                "/api/workflows/signing-tasks", "/api/desktop/launches", "/api/auth/me"}) {
            assertThat(call("GET", path, task, null).status()).as(path).isEqualTo(403);
        }
        assertThat(call("POST", "/api/workflows", task, "{}").status()).isEqualTo(403);
        assertThat(snapshot(wf)).isEqualTo(before);
        assertThat(snapshot(other)).isEqualTo(otherBefore);
    }

    @Test
    void noCodeIsIssuedForASlotWithoutItsCertificate() throws Exception {
        String wf = workflow("bob", null);
        var before = snapshot(wf);
        Reply r = call("POST", "/api/workflows/" + wf + "/launch-codes", login("bob"), null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CERTIFICATE_NOT_BOUND");
        assertThat(r.body()).doesNotContain("signflow://");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM launch_codes l JOIN workflows w"
                + " ON w.id = l.workflow_id WHERE w.workflow_id = ?", Long.class, wf)).isZero();
        assertThat(snapshot(wf)).isEqualTo(before);
        // Normal certificate registration by the signed-in signer still works.
        Reply bound = call("POST", "/api/workflows/" + wf + "/signers/bob/certificate",
                login("bob"), "{\"certificateSha256\":\"" + fp() + "\"}");
        assertThat(bound.status()).as(bound.body()).isEqualTo(200);
        assertThat(call("POST", "/api/workflows/" + wf + "/launch-codes", login("bob"), null)
                .status()).isEqualTo(201);
    }

    @Test
    void aBoundSlotLaunchesSignsAndThenTheSessionOnlyShowsTheResult() throws Exception {
        String wf = workflow("bob", mockFingerprint());
        String task = taskSession(wf, "bob");
        var view = new com.example.signer.server.api.ApiContracts.LaunchSessionView(task,
                "Bearer", 1800, wf, 1, "bob", "scope.pdf", "0.1.0");
        WorkflowApiClient client = WorkflowApiClient.forTask(base(), view);
        try {
            RemoteSigningService signing = new RemoteSigningService(
                    client, workflowSigningService, tokenService);
            Path dir = Files.createTempDirectory("task-sign");
            RemoteSigningService.ReviewPackage review = signing.prepareReview(wf, dir);
            simulator.insertToken();
            AtomicReference<Path> signed = new AtomicReference<>();
            SignatureSubmissionResponse response = signing.signRemoteStep(wf,
                    MockSigningToken.defaultPin(),
                    (f, o) -> new SignaturePlacement(0, 100f, 120f, 170f, 55f), null,
                    review.task().documentRevision(), dir,
                    new RemoteSigningService.Listener() {
                        @Override
                        public void signedFile(Path signedPdf) {
                            signed.set(signedPdf);
                        }
                    });
            assertThat(response.completed()).isTrue();
            var verification = workflowSigningService.load(signed.get()).verification();
            assertThat(SignatureStatus.of(verification.signatures().get(0), verification))
                    .isEqualTo(SignatureStatus.VALID);
        } finally {
            client.close();
        }
        // Finished: the status is still readable, nothing else.
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .isEqualTo(200);
        for (String[] r : new String[][] {{"GET", "/document"}, {"POST", "/claim"},
                {"POST", "/signatures"}}) {
            Reply reply = call(r[0], "/api/workflows/" + wf + r[1], task, null);
            assertThat(reply.status()).as(r[1]).isEqualTo(403);
            assertThat(reply.code()).isEqualTo("TASK_SESSION_FINISHED");
        }
    }

    @Test
    void cancellingTheWorkflowEndsTheSessionsAccessToTheDocument() throws Exception {
        String wf = workflow("bob", fp());
        String task = taskSession(wf, "bob");
        assertThat(call("GET", "/api/workflows/" + wf + "/document", task, null).status())
                .isEqualTo(200);
        assertThat(call("POST", "/api/workflows/" + wf + "/cancel", login("alice"), null)
                .status()).isIn(200, 204);
        Reply doc = call("GET", "/api/workflows/" + wf + "/document", task, null);
        assertThat(doc.status()).isEqualTo(403);
        assertThat(doc.code()).isEqualTo("TASK_SESSION_FINISHED");
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .isEqualTo(200);
    }

    @Test
    void anUnverifiedOrDisabledAccountLosesItsOpenSession() throws Exception {
        String wf = workflow("charlie", fp());
        String task = taskSession(wf, "charlie");
        try {
            jdbc.update("UPDATE users SET email_verified_at = NULL WHERE username = 'charlie'");
            assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                    .isEqualTo(401);
        } finally {
            jdbc.update("UPDATE users SET email_verified_at = now() WHERE username = 'charlie'");
        }
    }

    @Test
    void guestAndNormalUserCertificateRegistrationAreUnaffected() throws Exception {
        // A signed-in user still registers their own certificate.
        String wf = workflow("bob", null, "charlie", null);
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/bob/certificate",
                login("bob"), "{\"certificateSha256\":\"" + fp() + "\"}").status())
                .isEqualTo(200);
        // ...and only their own (Phase 3 rule unchanged).
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/charlie/certificate",
                login("bob"), "{\"certificateSha256\":\"" + fp() + "\"}").status())
                .isEqualTo(403);
        JsonNode view = mapper.readTree(call("GET", "/api/workflows/" + wf, login("alice"), null)
                .body());
        assertThat(view.toString()).contains("bob");
    }
}
