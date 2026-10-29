package com.example.signer.client;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client error mapping. The reported failure surfaced as
 * "the coordination server could not be reached: Cannot invoke
 * \"String.hashCode()\" because \"&lt;local4&gt;\" is null" — a NullPointerException
 * raised CLIENT-side while mapping an error response whose body was not the
 * {@code {code, message}} shape (the multipart 413 comes from the container,
 * not a controller). These tests pin the contract:
 *
 * <ul>
 *   <li>an error response never produces a NPE and is never reported as an
 *       unreachable server — the server answered;</li>
 *   <li>the status code alone yields an actionable message;</li>
 *   <li>a proper {@code ApiError} body is still mapped to its
 *       {@link ErrorCode};</li>
 *   <li>only a genuine connection failure says the server could not be
 *       reached.</li>
 * </ul>
 */
class WorkflowApiClientErrorTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void plainTextErrorBodyIsNotReportedAsUnreachable() throws Exception {
        String base = fakeServer(500, "Internal Server Error", "text/plain");
        WorkflowApiClient client = new WorkflowApiClient(base, null);

        assertThatThrownBy(client::myTasks)
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("could not be reached")
                .hasMessageNotContaining("hashCode")
                .hasMessageNotContaining("Exception");
    }

    @Test
    void empty413BodyProducesAFileSizeMessage() throws Exception {
        String base = fakeServer(413, "", "text/plain");
        WorkflowApiClient client = new WorkflowApiClient(base, null);

        assertThatThrownBy(client::myTasks)
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("larger than")
                .hasMessageContaining("HTTP 413");
    }

    @Test
    void apiErrorBodyIsStillMappedToItsErrorCode() throws Exception {
        String base = fakeServer(409,
                "{\"code\":\"NOT_YOUR_TURN\",\"message\":\"It is not your turn\"}",
                "application/json");
        WorkflowApiClient client = new WorkflowApiClient(base, null);

        assertThatThrownBy(client::myTasks)
                .isInstanceOf(SignerException.class)
                .hasMessage("It is not your turn")
                .satisfies(e -> assertThat(((SignerException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WORKFLOW_OUT_OF_TURN));
    }

    @Test
    void htmlErrorBodyFromAProxyStillExplainsTheStatus() throws Exception {
        String base = fakeServer(502, "<html><body>Bad Gateway</body></html>",
                "text/html");
        WorkflowApiClient client = new WorkflowApiClient(base, null);

        assertThatThrownBy(client::myTasks)
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("HTTP 502")
                .hasMessageNotContaining("could not be reached");
    }

    @Test
    void refusedConnectionIsReportedAsUnreachableServer() throws Exception {
        // Allocate a port, then close it: nothing listens there.
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        WorkflowApiClient client =
                new WorkflowApiClient("http://127.0.0.1:" + deadPort, null);

        assertThatThrownBy(client::myTasks)
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("Could not connect")
                .hasMessageContaining("Make sure it is running");
    }

    /** One-shot server answering every request with the given error. */
    private String fakeServer(int status, String body, String contentType)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
