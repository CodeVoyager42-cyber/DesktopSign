package com.example.signer.server.security;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase D1, risk D4: an account that has not verified its e-mail
 * address cannot sign, even when it holds a prepared slot of a real workflow
 * — and a verified one signs exactly as before.
 *
 * <p>One test per gated step of signing: claim the turn, download the
 * revision, submit the signature. Each runs against a workflow where
 * {@code bob} is a real signer with his certificate bound, so the only thing
 * standing between him and the action is the gate; the refusal is checked by
 * its {@code EMAIL_NOT_VERIFIED} code, which nothing else on those routes
 * emits.
 *
 * <p>The last test is the counterweight: the same account, verified, runs the
 * whole desktop signing flow through to a cryptographically valid signature.
 * Without it these tests would also pass if the gate refused everyone.
 *
 * <p>{@code email_verified_at} is moved in the test database and restored in
 * a {@code finally}, because the dev seeder marks its accounts verified and
 * the other tests of this class rely on that.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-d4-gate",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class UnverifiedSigningGateHttpTests {

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
        // D3: the per-address counts are shared and persistent now, so an
        // earlier class could have spent this address's budget.
        RateLimitTestSupport.clear(dataSource);
    }

    @AfterEach
    void tearDown() {
        // Whatever a test did, bob is verified again: the dev seeder marks
        // its accounts verified and every other suite relies on that.
        jdbc.update("UPDATE users SET email_verified_at = now()"
                + " WHERE username = 'bob' AND email_verified_at IS NULL");
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

    private String base() {
        return "http://localhost:" + port;
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

    private String login(String user) throws Exception {
        Reply r = call("POST", "/api/auth/login", null,
                "{\"username\":\"" + user + "\",\"password\":\"" + user + "-pass\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("accessToken").asText();
    }

    /** alice's workflow with bob as its only signer, certificate bound. */
    private String workflowForBob(String fingerprint) {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        specs.add(new CreateWorkflowRequest.SignerSpec(1, "bob", fingerprint));
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("d4-gate.pdf", "SEQUENTIAL", specs), "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), "alice");
        return view.workflowId();
    }

    private static byte[] pdf() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The mock token's fingerprint, so bob's slot is ready to sign. */
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

    private void unverify() {
        assertThat(jdbc.update("UPDATE users SET email_verified_at = NULL"
                + " WHERE username = 'bob'")).isEqualTo(1);
    }

    private static void assertRefusedAsUnverified(Reply reply, String what) {
        assertThat(reply.status()).as(what).isEqualTo(403);
        assertThat(reply.code()).as(what).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    /* ------------------------- one test per route ----------------------- */

    @Test
    void anUnverifiedAccountCannotClaimTheTurn() throws Exception {
        String wf = workflowForBob(mockFingerprint());
        String bob = login("bob");
        String claimedBefore = jdbc.queryForObject("SELECT coalesce(claimed_turn_username, '-')"
                + " FROM workflows WHERE workflow_id = ?", String.class, wf);

        unverify();
        assertRefusedAsUnverified(call("POST", "/api/workflows/" + wf + "/claim", bob, null),
                "POST /claim");

        assertThat(jdbc.queryForObject("SELECT coalesce(claimed_turn_username, '-')"
                + " FROM workflows WHERE workflow_id = ?", String.class, wf))
                .as("a refused claim must not take the turn").isEqualTo(claimedBefore);
    }

    @Test
    void anUnverifiedAccountCannotDownloadTheDocument() throws Exception {
        String wf = workflowForBob(mockFingerprint());
        String bob = login("bob");
        // Verified, the download works — so the refusal below is the gate.
        assertThat(call("GET", "/api/workflows/" + wf + "/document", bob, null).status())
                .isEqualTo(200);

        unverify();
        Reply refused = call("GET", "/api/workflows/" + wf + "/document", bob, null);

        assertRefusedAsUnverified(refused, "GET /document");
        assertThat(refused.body()).as("no PDF may come back with the refusal")
                .doesNotContain("%PDF");
        // HEAD is served by the same handler, so it is gated too. A HEAD
        // response carries no body by definition, so there is no JSON code to
        // check here — the status is the whole answer.
        Reply head = call("HEAD", "/api/workflows/" + wf + "/document", bob, null);
        assertThat(head.status()).as("HEAD /document").isEqualTo(403);
        assertThat(head.body()).isEmpty();
    }

    @Test
    void anUnverifiedAccountCannotSubmitASignature() throws Exception {
        String wf = workflowForBob(mockFingerprint());
        String bob = login("bob");
        long revisionsBefore = revisions(wf);

        unverify();
        // The gate runs before the controller, so the body never has to be a
        // well-formed multipart submission to be refused by it.
        assertRefusedAsUnverified(
                call("POST", "/api/workflows/" + wf + "/signatures", bob, "{}"),
                "POST /signatures");

        assertThat(revisions(wf)).as("a refused submission must not store a revision")
                .isEqualTo(revisionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_signers s JOIN workflows w"
                        + " ON w.id = s.workflow_id WHERE w.workflow_id = ? AND s.signed",
                Long.class, wf)).as("and must not mark the slot signed").isZero();
    }

    private long revisions(String wf) {
        return jdbc.queryForObject("SELECT count(*) FROM document_versions d JOIN workflows w"
                + " ON w.id = d.workflow_id WHERE w.workflow_id = ?", Long.class, wf);
    }

    /* --------------------- and the verified account signs --------------- */

    /**
     * The counterweight: the same account, verified, runs the whole desktop
     * flow — download, claim, sign on the token, submit — and the result
     * verifies. Nothing about the gate is in the way of a signer who has
     * proved their address.
     */
    @Test
    void aVerifiedAccountStillSignsTheDocument() throws Exception {
        String wf = workflowForBob(mockFingerprint());
        WorkflowApiClient client = WorkflowApiClient.login(base(), "bob", "bob-pass".toCharArray());
        try {
            RemoteSigningService signing = new RemoteSigningService(
                    client, workflowSigningService, tokenService);
            Path dir = Files.createTempDirectory("d4-gate-sign");
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

            assertThat(response.completed()).as("the workflow completes").isTrue();
            var verification = workflowSigningService.load(signed.get()).verification();
            assertThat(SignatureStatus.of(verification.signatures().get(0), verification))
                    .isEqualTo(SignatureStatus.VALID);
        } finally {
            client.close();
        }
        assertThat(revisions(wf)).as("the signed revision is stored").isPositive();
    }
}
