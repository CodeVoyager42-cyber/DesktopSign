package com.example.signer.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level tests for the JWT authentication lifecycle (Phase 3/4):
 * login, refresh-token rotation with reuse detection, logout revocation and
 * the Bearer-protected workflow API — against a real embedded server.
 */
@SpringBootTest(classes = com.example.signer.server.WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtAuthFlowTests {

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("jwt-auth-store").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    void capturePort(int port) {
        base = "http://localhost:" + port;
    }

    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void workflowsApiRequiresBearerAuthentication() throws Exception {
        assertThat(sendJson("GET", "/api/workflows/my-tasks", null, null).statusCode())
                .isEqualTo(401);
        // The old Basic scheme must no longer be accepted either.
        HttpResponse<String> basic = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/workflows/my-tasks"))
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString("alice:alice-pass".getBytes()))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(basic.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void loginReturnsTokenPair() throws Exception {
        HttpResponse<String> login = login("alice", "alice-pass");
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(login.body());
        assertThat(body.path("accessToken").asText()).isNotBlank();
        assertThat(body.path("refreshToken").asText()).isNotBlank();
        assertThat(body.path("expiresIn").asLong()).isBetween(60L, 3600L);
    }

    @Test
    @Order(3)
    void wrongPasswordIsRejected() throws Exception {
        assertThat(login("alice", "wrong").statusCode()).isEqualTo(401);
        assertThat(login("nobody", "nobody-pass").statusCode()).isEqualTo(401);
    }

    @Test
    @Order(4)
    void meEndpointValidatesAccessTokens() throws Exception {
        JsonNode tokens = mapper.readTree(login("alice", "alice-pass").body());
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                        .header("Authorization", "Bearer " + tokens.path("accessToken").asText())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("userId").asText()).isEqualTo("alice");

        // garbage and missing tokens are rejected
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                .header("Authorization", "Bearer garbage").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    @Test
    @Order(5)
    void refreshTokenRotatesAndOldTokenBecomesUnusable() throws Exception {
        JsonNode first = mapper.readTree(login("bob", "bob-pass").body());
        String refresh1 = first.path("refreshToken").asText();

        HttpResponse<String> refreshed = postRefresh(refresh1);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        JsonNode second = mapper.readTree(refreshed.body());
        assertThat(second.path("refreshToken").asText())
                .isNotEqualTo(refresh1);
        assertThat(second.path("accessToken").asText()).isNotBlank();

        // Rotation worked: the workflow API accepts the new access token.
        assertThat(authorizedTasks(second.path("accessToken").asText()).statusCode())
                .isEqualTo(200);
    }

    @Test
    @Order(6)
    void refreshReuseRevokesTheWholeFamily() throws Exception {
        JsonNode first = mapper.readTree(login("charlie", "charlie-pass").body());
        String refresh1 = first.path("refreshToken").asText();

        JsonNode second = mapper.readTree(postRefresh(refresh1).body());
        String refresh2 = second.path("refreshToken").asText();

        // Replaying the OLD (rotated) token = theft signal → family revoked.
        assertThat(postRefresh(refresh1).statusCode()).isEqualTo(401);

        // The descendant token from the same family is dead too.
        assertThat(postRefresh(refresh2).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(7)
    void logoutRevokesTheRefreshSession() throws Exception {
        JsonNode first = mapper.readTree(login("bob", "bob-pass").body());
        String refresh = first.path("refreshToken").asText();

        HttpResponse<String> logout = sendJson("POST", "/api/auth/logout",
                "{\"refreshToken\":\"" + refresh + "\"}", null);
        assertThat(logout.statusCode()).isEqualTo(204);

        // The revoked token no longer refreshes.
        assertThat(postRefresh(refresh).statusCode()).isEqualTo(401);

        // Logout is idempotent.
        assertThat(sendJson("POST", "/api/auth/logout",
                "{\"refreshToken\":\"" + refresh + "\"}", null).statusCode())
                .isEqualTo(204);
    }

    /* ------------------------------------------------------------------ */

    private static HttpResponse<String> login(String user, String password)
            throws Exception {
        return sendJson("POST", "/api/auth/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}",
                null);
    }

    private static HttpResponse<String> postRefresh(String refreshToken)
            throws Exception {
        return sendJson("POST", "/api/auth/refresh",
                "{\"refreshToken\":\"" + refreshToken + "\"}", null);
    }

    private static HttpResponse<String> authorizedTasks(String accessToken)
            throws Exception {
        return sendJson("GET", "/api/workflows/my-tasks", null, accessToken);
    }

    private static HttpResponse<String> sendJson(String method, String path,
                                                 String jsonBody,
                                                 String bearerToken)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        switch (method) {
            case "GET" -> builder.GET();
            case "POST" -> builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException(method);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
