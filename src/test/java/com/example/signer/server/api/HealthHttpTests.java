package com.example.signer.server.api;

import com.example.signer.server.WorkflowServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/health}: public, and nothing in it is sensitive.
 *
 * <p><b>Why this class exists.</b> These assertions used to live inside
 * {@code LaunchCodeHttpTests}, and they stay here now that the launch-code
 * path is back, because the health endpoint is NOT part of that path — as
 * is the {@code minDesktopVersion} it advertises, which
 * {@code HealthController} reads straight from
 * {@code signature.remote-server.desktop.min-version} and which is a
 * client-compatibility floor a client may check before it ever asks for a
 * launch code.
 *
 * <p>No database: the health endpoint does not touch one, which is the point
 * of it as a reachability probe, and that keeps this class runnable wherever
 * the suite runs — where {@code LaunchCodeHttpTests} needs PostgreSQL.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=false",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-health",
                "signature.remote-server.desktop.min-version=1.2.0"
        })
@ActiveProfiles("server")
class HealthHttpTests {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theHealthCheckIsPublicAndSaysNothingSensitive() throws Exception {
        HttpResponse<String> r = get("/api/health");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(r.body());

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("three fields, and no more: anything added here is public")
                .containsExactlyInAnyOrder("status", "apiVersion", "minDesktopVersion");

        assertThat(json.path("status").asText()).isEqualTo("UP");
        assertThat(json.path("minDesktopVersion").asText())
                .as("the configured floor is advertised verbatim")
                .isEqualTo("1.2.0");
    }

    /**
     * The probe account that an early revision of the deployment guide told
     * operators to use does not exist. Kept from the original test: it is the
     * reason the health route exists at all.
     */
    @Test
    void theOldProbeLoginStillFails() throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers
                        .ofString("{\"username\":\"probe\",\"password\":\"probe\"}"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
