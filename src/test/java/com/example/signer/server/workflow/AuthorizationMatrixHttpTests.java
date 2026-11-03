package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.security.RateLimitTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 3: the authorization matrix over real HTTP, database mode.
 *
 * <p>A document has exactly one signer — the account that uploaded it — so
 * the matrix has two actors: that account, and everybody else. Knowing a
 * workflow id must never grant anything.
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

    /** A document of {@code signer}'s, with that certificate (null = unbound). */
    private String workflow(String signer, String fingerprint) {
        WorkflowView view = workflows.createWorkflow(
                new CreateWorkflowRequest("authz.pdf", fingerprint), pdf(), signer);
        return view.workflowId();
    }

    /* ------------------------------- tests ------------------------------ */

    @Test
    void aStrangerGetsExactlyTheAnswerForAWorkflowThatDoesNotExist() throws Exception {
        String wf = workflow("bob", fp());
        String charlie = login("charlie");
        String missing = "WF-" + (900_000_000 + RANDOM.nextInt(99_999_999));
        String[][] probes = {
                {"GET", ""}, {"GET", "/task"}, {"GET", "/audit"}, {"GET", "/revisions"},
                {"GET", "/document"}, {"POST", "/cancel"}, {"POST", "/launch-codes"}};
        for (String[] p : probes) {
            Reply real = call(p[0], "/api/workflows/" + wf + p[1], charlie, null);
            Reply none = call(p[0], "/api/workflows/" + missing + p[1], charlie, null);
            assertThat(real.status()).as(p[0] + " " + p[1]).isEqualTo(404);
            assertThat(real.body()).as(p[0] + " " + p[1]).isEqualTo(none.body());
        }
        Reply bind = call("POST", "/api/workflows/" + wf + "/signers/charlie/certificate",
                charlie, "{\"certificateSha256\":\"" + fp() + "\"}");
        assertThat(bind.status()).isIn(403, 404);
        // And the workflow appears in none of the stranger's lists.
        for (String list : new String[] {"/my-tasks", "/owned", "/signing-tasks"}) {
            assertThat(call("GET", "/api/workflows" + list, charlie, null).body())
                    .as(list).doesNotContain(wf + "\"");
        }
    }

    @Test
    void theSignerReadsAndDownloadsItsOwnDocument() throws Exception {
        String wf = workflow("bob", fp());
        String bob = login("bob");

        for (String route : new String[] {"", "/task", "/audit", "/revisions", "/document"}) {
            assertThat(call("GET", "/api/workflows/" + wf + route, bob, null).status())
                    .as("GET " + route).isEqualTo(200);
        }
    }

    @Test
    void onlyTheOwnerCancelsAndOnlyWhileItIsUnsigned() throws Exception {
        String wf = workflow("bob", fp());
        String charlie = login("charlie");   // a stranger → the 404 of an unknown id
        String bob = login("bob");

        Reply stranger = call("POST", "/api/workflows/" + wf + "/cancel", charlie, null);
        assertThat(stranger.status()).isEqualTo(404);

        // Nothing changed: its owner still sees an active workflow.
        JsonNode view = mapper.readTree(call("GET", "/api/workflows/" + wf, bob, null).body());
        assertThat(view.path("status").asText()).isNotEqualTo("CANCELLED");

        assertThat(call("POST", "/api/workflows/" + wf + "/cancel", bob, null).status())
                .isEqualTo(200);
        // And a second cancel is a state error, not a second cancellation.
        Reply again = call("POST", "/api/workflows/" + wf + "/cancel", bob, null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_WORKFLOW_STATE");
    }

    @Test
    void aSlotKeepsItsFirstCertificateAndNobodyBindsForSomebodyElse() throws Exception {
        String wf = workflow("bob", null);
        String bob = login("bob");
        String charlie = login("charlie");

        Reply first = call("POST", "/api/workflows/" + wf + "/signers/bob/certificate", bob,
                "{\"certificateSha256\":\"" + fp() + "\"}");
        assertThat(first.status()).as(first.body()).isEqualTo(200);

        // A second certificate is refused. With one signer, binding the first
        // one also prepares the document and leaves DRAFT, so the state guard
        // answers before the already-bound guard is reached — either way the
        // slot keeps the certificate it was given.
        Reply rebind = call("POST", "/api/workflows/" + wf + "/signers/bob/certificate", bob,
                "{\"certificateSha256\":\"" + fp() + "\"}");
        assertThat(rebind.status()).isEqualTo(409);
        assertThat(rebind.code())
                .isIn("INVALID_WORKFLOW_STATE", "CERTIFICATE_ALREADY_BOUND");

        // Nobody binds for somebody else — not the slot's owner for another
        // name, and not another account for this slot.
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/charlie/certificate", bob,
                "{\"certificateSha256\":\"" + fp() + "\"}").status()).isEqualTo(403);
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/bob/certificate", charlie,
                "{\"certificateSha256\":\"" + fp() + "\"}").status()).isEqualTo(403);
    }

    @Test
    void aMalformedCertificateIsRefusedBeforeAnythingChanges() throws Exception {
        String wf = workflow("bob", null);
        String bob = login("bob");

        Reply refused = call("POST", "/api/workflows/" + wf + "/signers/bob/certificate", bob,
                "{\"certificateSha256\":\"not-a-fingerprint\"}");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("BAD_REQUEST");
        // Still DRAFT, still unbound: a good certificate is still accepted.
        assertThat(call("POST", "/api/workflows/" + wf + "/signers/bob/certificate", bob,
                "{\"certificateSha256\":\"" + fp() + "\"}").status()).isEqualTo(200);
    }
}
