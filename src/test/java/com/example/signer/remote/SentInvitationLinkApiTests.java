package com.example.signer.remote;

import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sender must be able to see the link their signers received, so the
 * desktop asks the server which base URL it emails from and keeps the link
 * returned by a resend. These tests pin the wire contract of both calls
 * against a stub server, including the status codes the desktop expects —
 * a resend that answers 204 (the old behaviour) must fail loudly here
 * rather than silently losing the freshly emailed link.
 */
class SentInvitationLinkApiTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void theResolvedBaseUrlAndItsSourceAreParsed() throws Exception {
        String base = fakeServer(200,
                "{\"baseUrl\":\"http://192.168.11.106:8080\","
                        + "\"localOnly\":false,"
                        + "\"source\":\"auto-detected LAN address\"}");

        InvitationLinkInfo info =
                new WorkflowApiClient(base, null).invitationLinkInfo();

        assertThat(info.baseUrl()).isEqualTo("http://192.168.11.106:8080");
        assertThat(info.localOnly()).isFalse();
        assertThat(info.source()).isEqualTo("auto-detected LAN address");
        assertThat(lastMethod.get()).isEqualTo("GET");
        assertThat(lastPath.get())
                .isEqualTo("/api/server-info/invitation-link");
    }

    @Test
    void aLocalOnlyBaseUrlIsParsedAsSuch() throws Exception {
        String base = fakeServer(200,
                "{\"baseUrl\":\"http://localhost:8080\",\"localOnly\":true,"
                        + "\"source\":\"APP_BASE_URL\"}");

        InvitationLinkInfo info =
                new WorkflowApiClient(base, null).invitationLinkInfo();

        assertThat(info.localOnly()).isTrue();
    }

    @Test
    void anOlderServerWithoutTheEndpointFailsVisibly() throws Exception {
        String base = fakeServer(404,
                "{\"code\":\"WORKFLOW_NOT_FOUND\",\"message\":\"Not found\"}");

        // The dialog catches this and still shows the invitations; the point
        // is that it must not be mistaken for a valid answer.
        assertThatThrownBy(() ->
                new WorkflowApiClient(base, null).invitationLinkInfo())
                .isInstanceOf(SignerException.class);
    }

    @Test
    void aResendReturnsTheFreshlyEmailedLink() throws Exception {
        String base = fakeServer(200,
                "{\"signerOrder\":2,\"email\":\"signer@example.com\","
                        + "\"invitationUrl\":\"http://192.168.11.106:8080"
                        + "/invitations/fresh-token\"}");

        InvitationLinkView sent =
                new WorkflowApiClient(base, null).resendInvitation(42L);

        assertThat(sent.signerOrder()).isEqualTo(2);
        assertThat(sent.email()).isEqualTo("signer@example.com");
        assertThat(sent.invitationUrl()).isEqualTo(
                "http://192.168.11.106:8080/invitations/fresh-token");
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastPath.get()).isEqualTo("/api/invitations/42/resend");
    }

    @Test
    void aResendThatAnswers204IsNotTreatedAsSuccess() throws Exception {
        // The pre-fix server answered 204 No Content: no link to show.
        String base = emptyServer(204);

        assertThatThrownBy(() ->
                new WorkflowApiClient(base, null).resendInvitation(42L))
                .isInstanceOf(SignerException.class);
    }

    /* ------------------------------------------------------------------ */

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    /** Stub server answering every request with a JSON body. */
    private String fakeServer(int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        return startServer(exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
    }

    /** Stub server answering every request with an empty body. */
    private String emptyServer(int status) throws IOException {
        return startServer(exchange -> {
            record(exchange);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    private void record(com.sun.net.httpserver.HttpExchange exchange) {
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(exchange.getRequestURI().getPath());
    }

    private String startServer(
            com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
