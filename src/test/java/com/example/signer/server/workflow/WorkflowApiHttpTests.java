package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level integration test (§38): exercises the REST surface itself —
 * JWT bearer authentication, multipart creation, document download and the
 * authorization rules — against a real embedded servlet server.
 * The cryptographic signing path is covered by
 * {@link RemoteWorkflowIntegrationTests}; here the HTTP contract is the
 * subject under test.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowApiHttpTests {

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("wf-http-store").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    void capturePort(int port) {
        base = "http://localhost:" + port;
    }

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    @AfterAll
    static void done() {
        // nothing to release: embedded server stops with the context
    }

    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void apiRequiresAuthentication() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/workflows/my-tasks")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void wrongPasswordIsRejected() throws Exception {
        assertThat(loginStatusCode("alice", "nope")).isEqualTo(401);
        HttpResponse<String> garbage = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/workflows/my-tasks"))
                        .header("Authorization", "Bearer not-a-real-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(garbage.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(3)
    void createTaskDownloadFlowOverHttp() throws Exception {
        // Build the request body: multipart with a real one-page PDF.
        byte[] pdf;
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        String boundary = "test-" + System.nanoTime();
        String json = "{\"documentName\":\"contract.pdf\",\"signers\":["
                + "{\"order\":1,\"userId\":\"alice\",\"certificateSha256\":\"" + fp("alice") + "\"},"
                + "{\"order\":2,\"userId\":\"bob\",\"certificateSha256\":\"" + fp("bob") + "\"}]}";
        byte[] body = concat(
                part(boundary, "request", "application/json", json),
                filePart(boundary, "file", "contract.pdf", pdf),
                ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> created = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/workflows"))
                        .header("Authorization", "Bearer " + bearer("alice"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String workflowId = mapperField(created.body(), "workflowId");

        // Task is visible to the assigned signer.
        HttpResponse<String> task = get("/api/workflows/" + workflowId + "/task", "alice");
        assertThat(task.statusCode()).isEqualTo(200);
        assertThat(task.body()).contains("\"signerOrder\":1");

        // The current signer can download the document.
        HttpResponse<byte[]> document = getBytes(
                "/api/workflows/" + workflowId + "/document", "alice");
        assertThat(document.statusCode()).isEqualTo(200);
        assertThat(document.body()).isNotEmpty();

        // A signer whose turn it is NOT cannot download it.
        HttpResponse<byte[]> denied = getBytes(
                "/api/workflows/" + workflowId + "/document", "bob");
        assertThat(denied.statusCode()).isEqualTo(409);

        // Unknown workflows are 404 for authenticated users.
        HttpResponse<String> missing = get("/api/workflows/WF-999999/task", "alice");
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    /* ------------------------------------------------------------------ */

    /** POSTs {@code /api/auth/login} and returns the HTTP status code. */
    private static int loginStatusCode(String user, String password) throws Exception {
        String json = "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}";
        return http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static final Map<String, String> accessTokens = new ConcurrentHashMap<>();

    /** Logs in once per user and caches the JWT access token. */
    private static String bearer(String user) {
        return accessTokens.computeIfAbsent(user, u -> {
            try {
                String json = "{\"username\":\"" + u + "\",\"password\":\"" + u + "-pass\"}";
                HttpResponse<String> login = http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json,
                                        StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (login.statusCode() != 200) {
                    throw new IllegalStateException("login failed for " + u);
                }
                return mapperField(login.body(), "accessToken");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static HttpResponse<String> get(String path, String user)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + bearer(user)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> getBytes(String path, String user)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + bearer(user)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String mapperField(String json, String field) {
        var start = json.indexOf("\"" + field + "\":");
        if (start < 0) {
            return null;
        }
        start = json.indexOf('"', json.indexOf(':', start)) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static byte[] part(String boundary, String name, String contentType, String content) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return concat(header.getBytes(StandardCharsets.UTF_8),
                content.getBytes(StandardCharsets.UTF_8),
                "\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] two(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] filePart(String boundary, String name, String filename,
                                   byte[] content) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        return concat(header.getBytes(StandardCharsets.UTF_8), content,
                "\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    private static Map<String, String> fingerprintCache;

    private String fp(String user) {
        if (fingerprintCache == null) {
            tokenService.disconnect();
            simulator.removeToken();
            simulator.insertToken();
            tokenService.detect();
            tokenService.startAuthentication();
            tokenService.authenticate(MockSigningToken.defaultPin());
            fingerprintCache = tokenService.getCertificates().stream()
                    .collect(Collectors.toMap(c -> c.getAlias(),
                            c -> c.getSha256Fingerprint()));
            tokenService.logout();
            tokenService.disconnect();
            simulator.removeToken();
        }
        return switch (user) {
            case "alice" -> fingerprintCache.get(MockSigningToken.DEFAULT_ALIAS);
            case "bob" -> fingerprintCache.get(MockSigningToken.THIRD_ALIAS);
            default -> throw new IllegalArgumentException(user);
        };
    }
}
