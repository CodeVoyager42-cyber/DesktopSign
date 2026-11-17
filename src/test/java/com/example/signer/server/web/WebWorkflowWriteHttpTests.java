package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.mail.EmailService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E part 2 over real HTTP: starting a workflow from the website,
 * inviting a slot, tracking what was sent, resending, revoking and
 * cancelling.
 *
 * <p>Three properties are the point of this file. Every state-changing route
 * needs its CSRF token and not merely a session. Every one of them is the
 * owner's alone, and a guessable invitation id does not stand in for being
 * the owner. And the Phase D gate reaches here through the service: an
 * account that has not confirmed its address cannot start a workflow,
 * although it may read (part 1).
 *
 * <p>Uses its own throwaway database, recreated for each run. Skipped when
 * PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-web-write-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebWorkflowWriteHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                // Many sign-ins from one address here, and no limit is under test.
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@Import(WebWorkflowWriteHttpTests.SilentMailbox.class)
@EnabledIf(value = "com.example.signer.server.web.WebWorkflowWriteHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class WebWorkflowWriteHttpTests {

    static final String DB = "signflow_web_write_test";

    /**
     * An invitation mailbox that never fails and keeps nothing. What the
     * e-mail says is not what this class is about, and a real sender would
     * make it depend on SMTP being reachable.
     */
    @TestConfiguration
    static class SilentMailbox {

        @Bean
        @Primary
        EmailService silentMailbox() {
            // An anonymous class rather than a lambda: EmailService has four
            // abstract methods now that the account mail is mandatory, so
            // there is no single method for a lambda to be. Every one is a
            // no-op here — this fixture exists so these tests do not send
            // mail, not to assert anything about it.
            return new EmailService() {
                @Override
                public void sendEmailVerification(String recipientEmail, String link,
                                                  Instant expiresAt) {
                }

                @Override
                public void sendPasswordReset(String recipientEmail, String link,
                                              Instant expiresAt) {
                }

                @Override
                public void sendPasswordChanged(String recipientEmail, Instant changedAt,
                                                boolean afterReset) {
                }

                @Override
                public void sendAccountLocked(String recipientEmail, Instant lockedUntil) {
                }
            };
        }
    }

    private static final String PASSWORD = "correct-password-1";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

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

    /**
     * A workflow started here belongs to the caller, and its signer slot
     * names an account.
     *
     * <p>This test used to name the signer by e-mail address and then check
     * that an invitation link came back exactly once and that the slot was
     * trackable by invitation status. Invitations are gone: a slot names an
     * existing account, and the response's invitation list is always empty.
     * What is still worth asserting is that creation succeeds, the workflow
     * is the caller's, and nothing claims an invitation was sent.
     */
    @Test
    void aWorkflowStartedHereIsOwnedAndNamesAnExistingAccount() throws Exception {
        Session owner = signIn(account(true));
        String signer = account(true);

        Reply created = owner.createWorkflow(slot(1, signer));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String workflow = created.json().path("workflow").path("workflowId").asText();
        assertThat(workflow).isNotBlank();

        assertThat(owner.get("/web/api/workflows/owned").body()).contains(workflow);
        assertThat(created.json().path("invitations"))
                .as("no invitation is created, so the list is empty")
                .isEmpty();
    }

    /**
     * The e-mail form of a signer slot is refused rather than taken for a
     * username. Accepted, it would create a workflow waiting on a signer who
     * cannot exist, and report success to the sender.
     */
    @Test
    void anEmailAddressIsNotAcceptedAsASignerSlot() throws Exception {
        Session owner = signIn(account(true));

        Reply refused = owner.createWorkflow(
                slot(1, "invitee-" + UUID.randomUUID() + "@example.test"));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.json().path("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(refused.body()).contains("not an e-mail address");
    }

    @Test
    void everyStateChangingRouteNeedsItsCsrfToken() throws Exception {
        Session owner = signIn(account(true));
        String workflow = owner.workflow(account(true));

        // A session, and no CSRF token: refused before anything happens.
        assertThat(owner.withoutCsrf().createWorkflow(slot(1, account(true))).status())
                .as("create without a CSRF token").isEqualTo(403);
        // The invitation routes were here too and went with them. What is
        // left is every state change the website can still make, and the
        // launch-code route is the one this removal put back — a POST that
        // mints a one-time credential is exactly what CSRF has to cover.
        String[][] posts = {
                {"/web/api/workflows/" + workflow + "/cancel", null},
                {"/web/api/workflows/" + workflow + "/launch-codes", null}};
        for (String[] post : posts) {
            assertThat(owner.withoutCsrf().post(post[0], post[1]).status())
                    .as("POST " + post[0] + " without a CSRF token").isEqualTo(403);
        }

        // Nothing was started, and the workflow is still running.
        assertThat(owner.get("/web/api/workflows/owned").body())
                .as("no second workflow").containsOnlyOnce("\"workflowId\"");
        assertThat(owner.get("/web/api/workflows/" + workflow).json().path("status").asText())
                .isNotEqualTo("CANCELLED");
    }

    /**
     * Only the owner may cancel. The invite and track probes that used to
     * stand beside this went with the invitation routes; cancelling is the
     * state change those rules still have to hold for.
     */
    @Test
    void onlyTheOwnerCancels() throws Exception {
        Session owner = signIn(account(true));
        String signerName = account(true);
        String workflow = owner.workflow(signerName);

        // A stranger cannot even see it, so cancelling is the uniform 404.
        Session stranger = signIn(account(true));
        Reply cancel = stranger.post("/web/api/workflows/" + workflow + "/cancel", null);
        Reply missing = stranger.post("/web/api/workflows/WF-"
                + (900_000_000 + RANDOM.nextInt(99_999_999)) + "/cancel", null);
        assertThat(cancel.status()).isEqualTo(404);
        assertThat(cancel.body()).isEqualTo(missing.body());

        // The owner can, and the state says so.
        assertThat(owner.post("/web/api/workflows/" + workflow + "/cancel", null).status())
                .isEqualTo(200);
        assertThat(owner.get("/web/api/workflows/" + workflow).json().path("status").asText())
                .isEqualTo("CANCELLED");
    }

    @Test
    void anUnverifiedAccountCannotStartAWorkflow() throws Exception {
        // The gate is in RemoteWorkflowService.createWorkflow, so it holds on
        // this route without the route knowing about it (phase-d §5).
        Session unverified = signIn(account(false));
        Reply refused = unverified.createWorkflow(slot(1, account(true)));

        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        assertThat(refused.json().path("code").asText()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(unverified.get("/web/api/workflows/owned").body()).isEqualTo("[]");
    }

    /* ------------------------------ helpers ----------------------------- */

    /** One signer slot as the JSON of {@code CreateWorkflowRequest}. */
    @Test
    void theHandOffScreenCanAskWhereToGetTheApplication() throws Exception {
        Session session = signIn(account(true));

        Reply info = session.get("/web/api/app-info");
        assertThat(info.status()).isEqualTo(200);
        assertThat(info.json().path("appName").asText()).isNotBlank();
        // Unset in this deployment, and null rather than "": a page testing
        // for a link should not have to know how absence is spelled. The
        // installer is built by scripts/package.sh and hosted nowhere, so
        // offering a link would mean offering one that 404s.
        assertThat(info.json().path("downloadUrl").isNull())
                .as("no download URL is configured here")
                .isTrue();

        // On the session chain like everything else: a route that needs no
        // session needs its own rate limit (Phase C risk C9).
        HttpResponse<String> anonymous = this.http.send(
                HttpRequest.newBuilder(uri("/web/api/app-info")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    private static String slot(int order, String userId) {
        return "{\"documentName\":\"phase-e.pdf\",\"signingPolicy\":\"SEQUENTIAL\","
                + "\"signers\":[{\"order\":" + order + ",\"userId\":\"" + userId
                + "\",\"certificateSha256\":\"" + fingerprint() + "\"}]}";
    }

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

    private record Reply(int status, String body, JsonNode json) {
    }

    /** A signed-in website session: its cookies, and its CSRF token. */
    private final class Session {

        private final String session;
        private final String csrf;
        private final boolean sendCsrf;

        private Session(String session, String csrf, boolean sendCsrf) {
            this.session = session;
            this.csrf = csrf;
            this.sendCsrf = sendCsrf;
        }

        /** The same session, but not echoing the token back. */
        Session withoutCsrf() {
            return new Session(this.session, this.csrf, false);
        }

        String cookies() {
            return sessionProperties.getCookieName() + "=" + this.session
                    + "; " + CSRF_COOKIE + "=" + this.csrf;
        }

        Reply get(String path) throws Exception {
            return reply(HttpRequest.newBuilder(uri(path)).GET()
                    .header("Cookie", cookies()).build());
        }

        Reply post(String path, String json) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                    .header("Cookie", cookies())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json));
            if (this.sendCsrf) {
                b.header("X-XSRF-TOKEN", this.csrf);
            }
            return reply(b.build());
        }

        /** POST /web/api/workflows as multipart: the request part, then the PDF. */
        Reply createWorkflow(String requestJson) throws Exception {
            String boundary = "----phase-e-" + UUID.randomUUID();
            HttpRequest.Builder b = HttpRequest.newBuilder(uri("/web/api/workflows"))
                    .header("Cookie", cookies())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            multipart(boundary, requestJson)));
            if (this.sendCsrf) {
                b.header("X-XSRF-TOKEN", this.csrf);
            }
            return reply(b.build());
        }

        /** A workflow owned by this session, with one slot for {@code signer}. */
        String workflow(String signer) throws Exception {
            Reply created = createWorkflow(slot(1, signer));
            assertThat(created.status()).as(created.body()).isEqualTo(201);
            return created.json().path("workflow").path("workflowId").asText();
        }
    }

    private Session signIn(String username) throws Exception {
        HttpResponse<String> bootstrap = this.http.send(
                HttpRequest.newBuilder(uri("/web/api/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bootstrap.statusCode()).isEqualTo(204);
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
        // Both are rotated at sign-in — the session id against fixation, and
        // the CSRF token with it — so the pair to keep is the one the login
        // response set, not the one the bootstrap handed out. A browser ends
        // up holding exactly this pair.
        return new Session(cookie(login, cookieName)
                .orElseThrow(() -> new AssertionError("no session cookie")),
                cookie(login, CSRF_COOKIE).orElse(token), true);
    }

    private Reply reply(HttpRequest request) throws Exception {
        HttpResponse<String> response =
                this.http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json;
        try {
            json = this.mapper.readTree(response.body());
        } catch (Exception notJson) {
            json = this.mapper.createObjectNode();
        }
        return new Reply(response.statusCode(), response.body(), json);
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

    /** The two parts POST /web/api/workflows takes, as one multipart body. */
    private static byte[] multipart(String boundary, String requestJson) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"request\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + requestJson + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"phase-e.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(pdf());
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String fingerprint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static byte[] pdf() {
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
