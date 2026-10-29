package com.example.signer.server.security;

import com.example.signer.server.WorkflowServerApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2: the per-address sign-in limit is part of the server's filter chain. */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"signature.remote-server.login.rate-limit=3",
                "signature.remote-server.login.rate-window-seconds=60"})
@ActiveProfiles("server")
class LoginRateLimitHttpTests {

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("rate-limit-http").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @LocalServerPort
    private int port;

    @Test
    void theFourthAttemptWithinAMinuteIsRefusedBeforeThePasswordIsChecked() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest wrong = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"alice\",\"password\":\"guess\"}")).build();
        for (int i = 0; i < 3; i++) {
            assertThat(http.send(wrong, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        HttpRequest right = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"alice\",\"password\":\"alice-pass\"}")).build();
        HttpResponse<String> limited = http.send(right, HttpResponse.BodyHandlers.ofString());
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.body()).doesNotContain("accessToken");
    }
}
