package com.example.signer.server.security;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.server.web.WebAccountService;
import com.example.signer.server.workflow.RemoteWorkflowService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase D1 risk D7: a password change ends the bearer tokens issued
 * before it, not only the sessions and refresh tokens.
 *
 * <p>Both kinds are covered, because the gap was wider than the risk's
 * wording. An access token lives 15 minutes; a desktop <b>task session</b>
 * lives 30, so closing only the access token would have left a window twice
 * as long as the one being closed.
 *
 * <p><b>Why these tests align to a second boundary.</b> A JWT's {@code iat}
 * is whole seconds, and the rule deliberately lets a token issued in the
 * change's own second stand — see
 * {@code DbUserAccounts.passwordChangedSince}, and
 * {@code DbUserAccountsTests.aTokenIssuedInTheSameSecondAsTheChangeStands},
 * which pins that trade-off down. So a test of the rule has to put the change
 * in a <i>later</i> second than the token, or it is not testing the rule at
 * all: written without the alignment these two passed or failed on where the
 * second boundary happened to fall, which is how the trade-off was found.
 *
 * <p>Its own throwaway database ({@value #DB}), deliberately: these tests
 * change a seeded account's password, and the classes that share
 * {@code signer_test} sign in as those accounts with the seeded one.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-d7-tokens",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + PasswordChangeEndsTokensHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.security"
        + ".PasswordChangeEndsTokensHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class PasswordChangeEndsTokensHttpTests {

    static final String DB = "signflow_d7_tokens_test";

    /** For the accounts this class creates itself. */
    private static final String PASSWORD = "a-perfectly-good-password-7";

    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WebAccountService webAccounts;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private RemoteWorkflowService workflows;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

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

    /* ------------------------------ helpers ----------------------------- */

    private record Reply(int status, String body) {
    }

    private Reply call(String method, String path, String bearer, String json)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
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

    private String login(String user, String password) throws Exception {
        Reply r = call("POST", "/api/auth/login", null,
                "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("accessToken").asText();
    }

    /** A request that only needs to be authenticated, not to be signing. */
    private int readTasks(String bearer) throws Exception {
        return call("GET", "/api/workflows/my-tasks", bearer, null).status();
    }

    /**
     * Waits out the rest of the current second, so that what happens next is
     * stamped in a later one. Sub-second and bounded: the alternative is a
     * test whose result depends on when it happened to start.
     */
    private static void intoTheNextSecond() {
        try {
            Thread.sleep(1_005 - System.currentTimeMillis() % 1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The {@code iat} the server put in a token, as it reads it back. */
    private Instant issuedAt(String jwt) throws Exception {
        return Instant.ofEpochSecond(mapper.readTree(
                Base64.getUrlDecoder().decode(jwt.split("\\.")[1])).path("iat").asLong());
    }

    private Instant storedChangeTime(String username) {
        Timestamp at = new JdbcTemplate(dataSource).queryForObject(
                "SELECT password_changed_at FROM users WHERE username = ?",
                Timestamp.class, username);
        return at == null ? null : at.toInstant();
    }

    /**
     * A fresh active account with {@link #PASSWORD}. Reading a task list
     * needs no verified address, so there is nothing else to set up.
     */
    private String account(String username) {
        String unique = username + System.nanoTime();
        users.save(new UserEntity(unique, unique + "@example.test",
                PasswordHashing.hash(PASSWORD), "Holder", AccountStatus.ACTIVE));
        return unique;
    }

    private static String fingerprint() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().withUpperCase().formatHex(b);
    }

    private static byte[] pdf() {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** alice's workflow with {@code signer} holding slot 1. */
    private String workflowFor(String signer) {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        specs.add(new CreateWorkflowRequest.SignerSpec(1, signer, null));
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("d7.pdf", "SEQUENTIAL", specs), "alice");
        return workflows.createWorkflow(prep.request(), pdf(), "alice").workflowId();
    }

    /**
     * A desktop task session for {@code user} on a workflow where it holds
     * slot 1: bind a certificate, issue a launch code, exchange it.
     */
    private String taskSession(String user, String password, String workflowId)
            throws Exception {
        String bearer = login(user, password);
        Reply bound = call("POST",
                "/api/workflows/" + workflowId + "/signers/" + user + "/certificate",
                bearer, "{\"certificateSha256\":\"" + fingerprint() + "\"}");
        assertThat(bound.status()).as(bound.body()).isIn(200, 204);
        Reply issued = call("POST", "/api/workflows/" + workflowId + "/launch-codes",
                bearer, null);
        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        String link = mapper.readTree(issued.body()).path("deepLink").asText();
        String code = link.substring(link.indexOf("&c=") + 3);
        Reply opened = call("POST", "/api/desktop/launch", null, mapper.writeValueAsString(
                Map.of("code", code, "appVersion", "9.9.9", "device", "d7-test")));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        return mapper.readTree(opened.body()).path("accessToken").asText();
    }

    /* ------------------------------- tests ------------------------------ */

    @Test
    void anAccessTokenIssuedBeforeTheChangeStopsWorking() throws Exception {
        String bearer = login("alice", "alice-pass");
        assertThat(readTasks(bearer)).as("it works to begin with").isEqualTo(200);

        intoTheNextSecond();
        assertThat(webAccounts.changePassword("alice", "alice-pass",
                "a-much-better-password-1", null)).isTrue();

        // The precondition the rule turns on, asserted rather than assumed:
        // without it this test could pass while proving nothing.
        assertThat(storedChangeTime("alice"))
                .as("the change must be stamped in a later second than the token")
                .isAfter(issuedAt(bearer));

        assertThat(readTasks(bearer))
                .as("the token outlived its 15 minutes before this fix, not its account")
                .isEqualTo(401);
    }

    @Test
    void andTheAccountCanSignInAgainImmediatelyAfterwards() throws Exception {
        // The guard must not outstay its welcome: the new token is issued
        // after the change, possibly in the very same second, and "reset,
        // then sign in again" is the path this feature exists to serve.
        String before = login("bob", "bob-pass");
        intoTheNextSecond();
        assertThat(webAccounts.changePassword("bob", "bob-pass",
                "another-fine-password-2", null)).isTrue();
        assertThat(readTasks(before)).isEqualTo(401);

        String after = login("bob", "another-fine-password-2");

        assertThat(readTasks(after)).as("the brand new token is not caught by the guard")
                .isEqualTo(200);
    }

    @Test
    void aTaskSessionIssuedBeforeTheChangeStopsWorkingToo() throws Exception {
        // The longer half of the risk: 30 minutes, not 15.
        String wf = workflowFor("charlie");
        String task = taskSession("charlie", "charlie-pass", wf);
        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .as("the one thing a task session is for").isEqualTo(200);

        intoTheNextSecond();
        assertThat(webAccounts.changePassword("charlie", "charlie-pass",
                "yet-another-password-3", null)).isTrue();

        assertThat(call("GET", "/api/workflows/" + wf + "/task", task, null).status())
                .as("a launch-code session is desktop access to the account as well")
                .isEqualTo(401);
    }

    @Test
    void oneAccountSChangeLeavesAnotherSTokenAlone() throws Exception {
        // Two accounts of its own, so this test does not depend on which of
        // the others has already changed a seeded password.
        String first = account("d7one");
        String second = account("d7two");
        String firstToken = login(first, PASSWORD);
        String secondToken = login(second, PASSWORD);

        intoTheNextSecond();
        assertThat(webAccounts.changePassword(first, PASSWORD,
                "a-replacement-password-9", null)).isTrue();

        assertThat(readTasks(firstToken)).isEqualTo(401);
        assertThat(readTasks(secondToken))
                .as("the check is per account, not a global cut-off time").isEqualTo(200);
    }
}
