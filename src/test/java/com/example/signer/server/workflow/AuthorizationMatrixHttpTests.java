package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.security.RateLimitTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import javax.sql.DataSource;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security Phase 3: the authorization matrix over real HTTP, database mode.
 * Actors: {@code alice} owns every workflow here; {@code bob} and
 * {@code charlie} are signers or strangers depending on the test.
 * Knowing a workflow id or an invitation id must never grant anything.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-authz",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                // Many logins from one address in this class.
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class AuthorizationMatrixHttpTests {

    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    private int port;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * D3: the per-address counts live in the shared test database now, so an
     * earlier class may have spent this address's budget. This class signs in
     * repeatedly and is not testing a limit.
     */
    @BeforeEach
    void clearRateLimits() {
        RateLimitTestSupport.clear(dataSource);
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
        return new Reply(r.statusCode(), r.body());
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

    /** alice's workflow with the given signer slots (userId, fingerprint-or-null). */
    private String workflow(String... slots) {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        for (int i = 0; i < slots.length; i += 2) {
            specs.add(new CreateWorkflowRequest.SignerSpec(i / 2 + 1, slots[i], slots[i + 1]));
        }
        WorkflowView view = workflows.createWorkflow(
                new CreateWorkflowRequest("authz.pdf", "SEQUENTIAL", specs), pdf(), "alice");
        return view.workflowId();
    }

    /* ------------------------------- tests ------------------------------ */

    @Test
    void aStrangerGetsExactlyTheAnswerForAWorkflowThatDoesNotExist() throws Exception {
        String wf = workflow("bob", fp());
        String charlie = login("charlie");
        String missing = "WF-" + (900_000_000 + RANDOM.nextInt(99_999_999));
        // GET /invitations was in this list and went with the invitation
        // routes. POST /launch-codes replaces it: a stranger must get the
        // same uniform 404 from the hand-off as from everything else, or the
        // route becomes a way to learn that a workflow exists.
        String[][] probes = {
                {"GET", ""}, {"GET", "/task"}, {"GET", "/audit"}, {"GET", "/revisions"},
                {"GET", "/document"}, {"GET", "/eligible-signers"},
                {"POST", "/claim"}, {"POST", "/cancel"}, {"POST", "/launch-codes"}};
        for (String[] p : probes) {
            Reply real = call(p[0], "/api/workflows/" + wf + p[1], charlie, null);
            Reply none = call(p[0], "/api/workflows/" + missing + p[1], charlie, null);
            assertThat(real.status()).as(p[0] + " " + p[1]).isEqualTo(404);
            assertThat(real.body()).as(p[0] + " " + p[1]).isEqualTo(none.body());
        }
        Reply bind = call("POST", "/api/workflows/" + wf + "/signers/charlie/certificate",
                charlie, "{\"certificateSha256\":\"" + fp() + "\"}");
        assertThat(bind.status()).isEqualTo(404);
        // And the workflow appears in none of the stranger's lists.
        for (String list : new String[] {"/my-tasks", "/owned", "/signing-tasks"}) {
            assertThat(call("GET", "/api/workflows" + list, charlie, null).body())
                    .as(list).doesNotContain(wf + "\"");
        }
    }

    @Test
    void theOwnerWhoIsNotASignerCanWatchButNotClaimDownloadOrSign() throws Exception {
        String wf = workflow("bob", fp());
        String alice = login("alice");
        assertThat(call("GET", "/api/workflows/" + wf, alice, null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/workflows/" + wf + "/audit", alice, null).status())
                .isEqualTo(200);
        Reply claim = call("POST", "/api/workflows/" + wf + "/claim", alice, null);
        assertThat(claim.status()).isEqualTo(403);
        assertThat(claim.code()).isEqualTo("WORKFLOW_NOT_ACCESSIBLE");
        Reply document = call("GET", "/api/workflows/" + wf + "/document", alice, null);
        assertThat(document.status()).isEqualTo(409);
        assertThat(document.code()).isEqualTo("NOT_YOUR_TURN");
        assertThatThrownBy(() -> workflows.submitSignature(wf, "alice", pdf(),
                new com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest(
                        0, null, "op-1")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.NOT_YOUR_TURN);
    }

    @Test
    void aSignerWhoseTurnItIsNotCannotDownloadOrSign() throws Exception {
        String wf = workflow("bob", fp(), "charlie", fp());
        String charlie = login("charlie");
        assertThat(call("GET", "/api/workflows/" + wf + "/task", charlie, null).status())
                .isEqualTo(200);
        Reply document = call("GET", "/api/workflows/" + wf + "/document", charlie, null);
        assertThat(document.status()).isEqualTo(409);
        assertThat(document.code()).isEqualTo("NOT_YOUR_TURN");
        Reply claim = call("POST", "/api/workflows/" + wf + "/claim", charlie, null);
        assertThat(claim.status()).isEqualTo(409);
    }

    @Test
    void aSlotKeepsItsFirstCertificateAndACertificateHoldsOneSlot() throws Exception {
        String wf = workflow("bob", null, "charlie", null);
        String bob = login("bob");
        String charlie = login("charlie");
        String bobsCert = fp();

        Reply first = call("POST", "/api/workflows/" + wf + "/signers/bob/certificate", bob,
                "{\"certificateSha256\":\"" + bobsCert + "\"}");
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        Reply rebind = call("POST", "/api/workflows/" + wf + "/signers/bob/certificate", bob,
                "{\"certificateSha256\":\"" + fp() + "\"}");
        assertThat(rebind.status()).isEqualTo(409);
        assertThat(rebind.code()).isEqualTo("CERTIFICATE_ALREADY_BOUND");

        Reply sameCert = call("POST", "/api/workflows/" + wf + "/signers/charlie/certificate",
                charlie, "{\"certificateSha256\":\"" + bobsCert + "\"}");
        assertThat(sameCert.status()).isEqualTo(409);
        assertThat(sameCert.code()).isEqualTo("CERTIFICATE_ALREADY_BOUND");

        // Nobody binds for somebody else.
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/charlie/certificate", bob,
                "{\"certificateSha256\":\"" + fp() + "\"}").status()).isEqualTo(403);

        // The sender cannot pre-bind one certificate to two slots either.
        String shared = fp();
        assertThatThrownBy(() -> workflow("bob", shared, "charlie", shared))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
    }
}
