package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.WorkflowOperations;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.workflow.RemoteWorkflowService;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E part 3 over real HTTP: the finished document, and who may have it.
 *
 * <p>Gap G6 is what this closes. Until now the only download was the signing
 * one — the current revision, for the one signer whose turn it is — so the
 * person who sent a contract out had no way to get the signed copy back.
 *
 * <p>The workflow here is signed for real: a one-slot workflow bound to the
 * mock smart card's certificate, signed with the same signing service the
 * desktop uses, submitted and accepted. A faked {@code COMPLETED} state would
 * have tested the route and not the thing the route is for.
 *
 * <p>Uses its own throwaway database, recreated for each run. Skipped when
 * PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-web-download-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebWorkflowDownloadHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.web.WebWorkflowDownloadHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class WebWorkflowDownloadHttpTests {

    static final String DB = "signflow_web_download_test";

    private static final String PASSWORD = "correct-password-1";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private WebSessionProperties sessionProperties;

    @Autowired
    private WorkflowSigningService signing;

    @Autowired
    private TokenService token;

    @Autowired
    private MockTokenSimulator card;

    @AfterEach
    void putTheCardAway() {
        this.token.disconnect();
        this.card.removeToken();
    }

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
    void theFinishedDocumentGoesToEveryoneWhoTookPart() throws Exception {
        String owner = account();
        String signer = account();
        String workflow = signedWorkflow(owner, signer, "quarterly report.pdf");

        // The sender, who signed nothing and could never download before.
        HttpResponse<byte[]> sent = download(workflow, signIn(owner));
        assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(sent.headers().firstValue("content-type"))
                .hasValue("application/pdf");
        assertThat(sent.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"quarterly report.pdf\"");
        assertThat(new String(sent.body(), 0, 5, StandardCharsets.ISO_8859_1))
                .as("a PDF, not an error page").isEqualTo("%PDF-");

        // And the signer, whose turn is long over.
        HttpResponse<byte[]> signed = download(workflow, signIn(signer));
        assertThat(signed.statusCode()).isEqualTo(200);
        assertThat(signed.body()).isEqualTo(sent.body());
    }

    @Test
    void beforeItIsFinishedThereIsNothingToDownload() throws Exception {
        String owner = account();
        String signer = account();
        String workflow = workflow(owner, signer, "contract.pdf");

        // Waiting for its signature: a half-signed revision is a working
        // copy, and neither the sender nor the signer gets it from here.
        for (String caller : new String[] {owner, signer}) {
            HttpResponse<byte[]> refused = download(workflow, signIn(caller));
            assertThat(refused.statusCode()).as("download as " + caller).isEqualTo(409);
            assertThat(new String(refused.body(), StandardCharsets.UTF_8))
                    .contains("INVALID_WORKFLOW_STATE");
        }
    }

    @Test
    void aStrangerGetsExactlyTheAnswerForAWorkflowThatDoesNotExist() throws Exception {
        String workflow = signedWorkflow(account(), account(), "private.pdf");
        String stranger = signIn(account());
        String missing = "WF-" + (900_000_000 + RANDOM.nextInt(99_999_999));

        HttpResponse<byte[]> real = download(workflow, stranger);
        HttpResponse<byte[]> none = download(missing, stranger);

        assertThat(real.statusCode()).isEqualTo(404);
        assertThat(real.body()).isEqualTo(none.body());
        // Nothing of the document, and not even its name, is in the answer.
        assertThat(new String(real.body(), StandardCharsets.UTF_8))
                .doesNotContain("private.pdf");
    }

    @Test
    void withoutASessionThereIsNoDownload() throws Exception {
        String workflow = signedWorkflow(account(), account(), "contract.pdf");

        HttpResponse<byte[]> refused = this.http.send(
                HttpRequest.newBuilder(uri("/web/api/workflows/" + workflow + "/download"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(refused.statusCode()).isEqualTo(401);
    }

    @Test
    void aDocumentNameCannotCarryAnythingIntoTheHeader() {
        // The name is the uploader's text, and only non-blankness is checked
        // of it. A quote would end the filename early and leave the rest to
        // be read as further parameters; a newline is a header-splitting
        // attempt. Both are flattened, and a name with nothing usable left
        // becomes a name of ours.
        assertThat(WorkflowOperations.attachment("report\".pdf"))
                .isEqualTo("attachment; filename=\"report_.pdf\"");
        // The space survives, because filenames legitimately contain them;
        // the carriage return, the newline, the colon and the equals sign do
        // not, which is what a split needs.
        assertThat(WorkflowOperations.attachment("a\r\nSet-Cookie: x=y"))
                .isEqualTo("attachment; filename=\"a__Set-Cookie_ x_y\"");
        assertThat(WorkflowOperations.attachment("\"\";"))
                .isEqualTo("attachment; filename=\"document.pdf\"");
        assertThat(WorkflowOperations.attachment(null))
                .isEqualTo("attachment; filename=\"document.pdf\"");
        // An ordinary name is left as it is.
        assertThat(WorkflowOperations.attachment("Q3 report (final).pdf"))
                .isEqualTo("attachment; filename=\"Q3 report _final_.pdf\"");
    }

    /* ------------------------------ helpers ----------------------------- */

    /** A verified account with a known password. */
    private String account() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity account = new UserEntity(name, name + "@example.test",
                PasswordHashing.hash(PASSWORD), name, AccountStatus.ACTIVE);
        account.setEmailVerifiedAt(Instant.now());
        this.users.save(account);
        return name;
    }

    /** A workflow of {@code owner} with one slot, held by {@code signer}. */
    private String workflow(String owner, String signer, String documentName)
            throws Exception {
        return this.workflows.createWorkflow(
                new CreateWorkflowRequest(documentName, "SEQUENTIAL",
                        List.of(new CreateWorkflowRequest.SignerSpec(
                                1, signer, cardFingerprint()))),
                onePagePdf(), owner).workflowId();
    }

    /**
     * The same workflow, actually signed: the document is fetched the way the
     * desktop fetches it, signed with the mock card, and submitted.
     */
    private String signedWorkflow(String owner, String signer, String documentName)
            throws Exception {
        String workflow = workflow(owner, signer, documentName);

        Path dir = Files.createTempDirectory("web-download");
        Path unsigned = dir.resolve("unsigned.pdf");
        Path signed = dir.resolve("signed.pdf");
        Files.write(unsigned, this.workflows.getDocument(workflow, signer));

        connectCard();
        this.signing.signNextStep(unsigned, null, signed);
        assertThat(this.workflows.submitSignature(workflow, signer,
                Files.readAllBytes(signed),
                new SignatureSubmissionRequest(0, null, "op-" + workflow))
                .completed()).as("the one signature completes it").isTrue();
        assertThat(this.workflows.getWorkflow(workflow, owner).status())
                .isEqualTo("COMPLETED");
        return workflow;
    }

    private HttpResponse<byte[]> download(String workflow, String session)
            throws Exception {
        return this.http.send(
                HttpRequest.newBuilder(uri("/web/api/workflows/" + workflow + "/download"))
                        .header("Cookie", this.sessionProperties.getCookieName()
                                + "=" + session)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Signs in and returns the session cookie value. */
    private String signIn(String username) throws Exception {
        HttpResponse<String> bootstrap = this.http.send(
                HttpRequest.newBuilder(uri("/web/api/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String token = cookie(bootstrap, CSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie"));
        String cookieName = this.sessionProperties.getCookieName();
        String header = CSRF_COOKIE + "=" + token
                + cookie(bootstrap, cookieName).map(s -> "; " + cookieName + "=" + s)
                        .orElse("");

        HttpResponse<String> login = this.http.send(
                HttpRequest.newBuilder(uri("/web/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", header)
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"" + username + "\",\"password\":\""
                                        + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return cookie(login, cookieName)
                .orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + this.port + path);
    }

    private static Optional<String> cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .map(h -> h.substring(name.length() + 1).split(";", 2)[0])
                .filter(v -> !v.isEmpty())
                .findFirst();
    }

    /** Inserts the mock card and authenticates it, as a signer would. */
    private void connectCard() {
        this.token.disconnect();
        this.card.removeToken();
        this.card.insertToken();
        this.token.detect();
        this.token.startAuthentication();
        this.token.authenticate(MockSigningToken.defaultPin());
    }

    /** The fingerprint of the certificate the mock card will sign with. */
    private String cardFingerprint() {
        connectCard();
        String fingerprint = this.token.getCertificates().stream()
                .filter(c -> MockSigningToken.DEFAULT_ALIAS.equals(c.getAlias()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the mock card has no default key"))
                .getSha256Fingerprint();
        this.token.logout();
        this.token.disconnect();
        this.card.removeToken();
        return fingerprint;
    }

    private static byte[] onePagePdf() {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
