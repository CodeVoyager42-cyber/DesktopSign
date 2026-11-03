package com.example.signer.client;

import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.12 / P0.9 / P0.8 client regression tests against a scripted local
 * server: 401 → single-flight refresh → exactly ONE Authorization header on
 * the retry; refresh failure logs the session out; plain HTTP is refused
 * for non-loopback servers; idempotent submission retries only on
 * transport failures and always with the same operation id.
 */
class WorkflowApiClientRefreshTests {

    private HttpServer server;

    /** Authorization header values of every workflow-API request, in order. */
    private final List<List<String>> authHeaders = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private final AtomicInteger workflowCalls = new AtomicInteger();
    private volatile String validAccess = "access-1";
    private volatile String validRefresh = "refresh-0";
    private volatile boolean refreshRefused;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        WorkflowApiClient.allowInsecureTransportForDevelopment(false);
    }

    private String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/api/auth/refresh", exchange -> {
            refreshCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            synchronized (this) {
                if (refreshRefused || !body.contains("\"" + validRefresh + "\"")) {
                    respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
                    return;
                }
                sleep(150); // widen the race window for concurrent callers
                int n = Integer.parseInt(validRefresh.substring("refresh-".length())) + 1;
                validRefresh = "refresh-" + n;
                validAccess = "access-new-" + n;
                respond(exchange, 200, "{\"accessToken\":\"" + validAccess
                        + "\",\"expiresIn\":900,\"refreshToken\":\"" + validRefresh + "\"}");
            }
        });
        server.createContext("/api/workflows", exchange -> {
            workflowCalls.incrementAndGet();
            List<String> auth = exchange.getRequestHeaders().get("Authorization");
            authHeaders.add(auth == null ? List.of() : List.copyOf(auth));
            if (auth == null || auth.size() != 1
                    || !auth.get(0).equals("Bearer " + validAccess)) {
                respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
                return;
            }
            respond(exchange, 200, "[]");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private WorkflowApiClient clientWithExpiredToken(String base) {
        validAccess = "access-1";
        return WorkflowApiClient.withSession(base, "alice",
                new TokenPairResponse("expired-access", 900, "refresh-0"));
    }

    @Test
    void expiredTokenTriggersOneRefreshAndTheRetryCarriesExactlyOneFreshHeader()
            throws Exception {
        WorkflowApiClient client = clientWithExpiredToken(start());

        assertThat(client.myTasks()).isEmpty();

        assertThat(refreshCalls).hasValue(1);
        assertThat(authHeaders).hasSize(2);
        assertThat(authHeaders.get(0)).containsExactly("Bearer expired-access");
        // The retry: ONE header, the NEW token, never the expired one.
        assertThat(authHeaders.get(1)).containsExactly("Bearer access-new-1");
    }

    @Test
    void concurrentCallsWithAnExpiredTokenShareASingleRefresh() throws Exception {
        WorkflowApiClient client = clientWithExpiredToken(start());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Object> call = () -> {
                    gate.await();
                    return client.myTasks();
                };
                results.add(pool.submit(call));
            }
            gate.countDown();
            for (Future<Object> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
        // One rotation only: presenting the same refresh token twice would be
        // treated as token theft by the real server and kill the session.
        assertThat(refreshCalls).hasValue(1);
        assertThat(authHeaders).allSatisfy(h -> assertThat(h).hasSize(1));
    }

    @Test
    void aRefusedRefreshLogsTheSessionOut() throws Exception {
        WorkflowApiClient client = clientWithExpiredToken(start());
        refreshRefused = true;

        assertThatThrownBy(client::myTasks)
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("Sign in again");
        int callsAfterLogout = workflowCalls.get();

        // Logged out locally: no further request leaves the machine with the
        // dead tokens.
        assertThatThrownBy(client::myTasks)
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("Sign in again");
        assertThat(workflowCalls).hasValue(callsAfterLogout);
        assertThat(client.sessionForTest().isClosed()).isTrue();
        assertThat(client.sessionForTest().accessToken()).isNull();
        assertThat(client.sessionForTest().refreshToken()).isNull();
    }

    @Test
    void plainHttpIsRefusedForRemoteServersOutsideDevelopment() {
        assertThatThrownBy(() -> new WorkflowApiClient("http://signflow.example.com", null))
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("plain HTTP");
        assertThatThrownBy(() -> new WorkflowApiClient("http://192.168.1.10:8080", null))
                .isInstanceOf(SignerException.class);
        assertThatCode(() -> new WorkflowApiClient("https://signflow.example.com", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new WorkflowApiClient("http://localhost:8080", null))
                .doesNotThrowAnyException();

        WorkflowApiClient.allowInsecureTransportForDevelopment(true);
        assertThatCode(() -> new WorkflowApiClient("http://192.168.1.10:8080", null))
                .doesNotThrowAnyException();
    }

    /* ── P0.8: idempotent submission retry ─────────────────────────────── */

    @Test
    void lostResponseIsRetriedWithTheSameOperationIdAndNonTransportErrorsAreNot()
            throws Exception {
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger submissions = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/workflows/WF-1/signatures", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1));
            if (submissions.incrementAndGet() == 1) {
                // Accepted, but the response never reaches the client.
                exchange.close();
                return;
            }
            respond(exchange, 200, "{\"workflowId\":\"WF-1\",\"acceptedRevision\":1,"
                    + "\"status\":\"WAITING_FOR_SIGNER\",\"nextSignerUserId\":\"bob\","
                    + "\"completed\":false}");
        });
        server.createContext("/api/workflows/WF-2/signatures", exchange -> {
            submissions.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 409, "{\"code\":\"STALE_REVISION\",\"message\":\"stale\"}");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        WorkflowApiClient client = WorkflowApiClient.withSession(base, "alice",
                new TokenPairResponse("a", 900, "r"));

        var response = RemoteSigningService.submitIdempotently(client, "WF-1",
                new SignatureSubmissionRequest(0, null, "op-123"), new byte[] {1, 2, 3}, 3, 0);
        assertThat(response.acceptedRevision()).isEqualTo(1);
        assertThat(submissions).hasValue(2);
        assertThat(bodies).hasSize(2).allSatisfy(b -> assertThat(b).contains("op-123"));

        submissions.set(0);
        assertThatThrownBy(() -> RemoteSigningService.submitIdempotently(client, "WF-2",
                new SignatureSubmissionRequest(0, null, "op-456"), new byte[] {1}, 3, 0))
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("stale");
        assertThat(submissions).as("a server answer is final, never retried").hasValue(1);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
