package com.example.signer.server.launch;

import com.example.signer.server.WorkflowServerApplication;

import com.example.signer.server.security.RateLimitTestSupport;

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

import static org.assertj.core.api.Assertions.assertThat;

/** Phase B: guessing launch codes is limited per address, like sign-in. */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-launch-rl",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.login.rate-limit=4"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class LaunchExchangeRateLimitTests {

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    /**
     * This test asserts the exact sequence of statuses a budget of 4
     * produces, so it can only be right if it starts from zero — and since
     * D3 the count is in the shared test database, where an earlier class
     * may have spent some of it.
     */
    @BeforeEach
    void startFromAnEmptyBudget() {
        RateLimitTestSupport.clear(dataSource);
    }

    @Test
    void guessingCodesIsCutOffAfterTheLimit() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        int[] statuses = new int[6];
        for (int i = 0; i < statuses.length; i++) {
            String body = "{\"code\":\"" + String.valueOf((char) ('a' + i)).repeat(43)
                    + "\",\"appVersion\":\"9.9.9\"}";
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/desktop/launch"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            statuses[i] = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
        assertThat(statuses).containsExactly(400, 400, 400, 400, 429, 429);
        // Its own bucket: the same address can still sign in.
        HttpRequest login = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"bob\",\"password\":\"bob-pass\"}")).build();
        assertThat(http.send(login, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(200);
    }
}
