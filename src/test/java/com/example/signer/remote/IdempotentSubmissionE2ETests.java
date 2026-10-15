package com.example.signer.remote;

import com.example.signer.server.RemoteWorkflowService;
import com.example.signer.server.WorkflowFileStore;
import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0.8 end to end over real HTTP: the coordination server ACCEPTS the
 * signature, but the response is lost on the way back (the connection dies,
 * as on a client timeout). The desktop retries with the SAME operation id
 * and receives the original result; the server stores exactly one new
 * revision.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
@TestPropertySource(properties = "signature.remote-server.storage=${java.io.tmpdir}/signer-idem-e2e")
class IdempotentSubmissionE2ETests {

    @LocalServerPort
    private int port;

    @Autowired
    private RemoteWorkflowService service;

    @Autowired
    private WorkflowFileStore store;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    private ResponseDroppingProxy proxy;

    @AfterEach
    void cleanUp() throws IOException {
        tokenService.disconnect();
        simulator.removeToken();
        if (proxy != null) {
            proxy.close();
        }
    }

    @Test
    void aRetryAfterALostResponseReturnsTheOriginalRevisionWithoutASecondOne()
            throws Exception {
        String fp = fingerprint(MockSigningToken.DEFAULT_ALIAS);
        String bobFp = fingerprint(MockSigningToken.THIRD_ALIAS);
        String id = service.createWorkflow(new CreateWorkflowRequest("contract.pdf",
                "SEQUENTIAL", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", bobFp))),
                pdf(), "alice").workflowId();
        byte[] signed = sign(service.getDocument(id, "alice"));

        String direct = "http://localhost:" + port;
        proxy = new ResponseDroppingProxy(port);
        WorkflowApiClient.Session session = WorkflowApiClient.Session.login(direct,
                "alice", "alice-pass");
        WorkflowApiClient client = new WorkflowApiClient(
                "http://127.0.0.1:" + proxy.port(), session);

        SignatureSubmissionResponse response = RemoteSigningService.submitIdempotently(
                client, id, new SignatureSubmissionRequest(0, null, "op-lost-response"),
                signed, 3, 0);

        assertThat(proxy.droppedResponses).hasValue(1);
        assertThat(proxy.submissions).hasValue(2);
        assertThat(response.acceptedRevision()).isEqualTo(1);
        assertThat(response.nextSignerUserId()).isEqualTo("bob");
        assertThat(store.load(id).revisions()).hasSize(2);
        assertThat(store.existsRevision(id, 2)).isFalse();
        assertThat(store.load(id).signers().get(0).signingOperationId())
                .isEqualTo("op-lost-response");
        client.close();
    }

    private byte[] sign(byte[] document) throws Exception {
        Path dir = Files.createTempDirectory("idem-e2e");
        Path in = dir.resolve("rev0.pdf");
        Files.write(in, document);
        connectToken();
        Path out = dir.resolve("rev1.pdf");
        workflowSigningService.signNextStep(in, null, out, 1);
        return Files.readAllBytes(out);
    }

    private String fingerprint(String alias) {
        connectToken();
        String fp = tokenService.getCertificates().stream()
                .filter(c -> alias.equals(c.getAlias()))
                .map(SigningCertificate::getSha256Fingerprint)
                .findFirst().orElseThrow();
        tokenService.disconnect();
        simulator.removeToken();
        return fp;
    }

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    private static byte[] pdf() throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * TCP proxy in front of the server. The FIRST connection that carries a
     * signature submission is forwarded to the server, but once the server
     * starts answering, the client side is closed without passing on a byte:
     * the server committed, the client never learns it.
     */
    static final class ResponseDroppingProxy implements AutoCloseable {
        final AtomicInteger submissions = new AtomicInteger();
        final AtomicInteger droppedResponses = new AtomicInteger();
        private final ServerSocket listener;
        private final int target;

        ResponseDroppingProxy(int target) throws IOException {
            this.target = target;
            this.listener = new ServerSocket();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread acceptor = new Thread(this::acceptLoop, "drop-proxy");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    Thread t = new Thread(() -> handle(client), "drop-proxy-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket client) {
            try (client; Socket upstream = new Socket("127.0.0.1", target)) {
                InputStream fromClient = client.getInputStream();
                byte[] first = new byte[8192];
                int n = fromClient.read(first);
                if (n <= 0) {
                    return;
                }
                String head = new String(first, 0, n, StandardCharsets.ISO_8859_1);
                boolean submission = head.startsWith("POST ") && head.contains("/signatures ");
                boolean drop = submission && submissions.incrementAndGet() == 1;
                upstream.getOutputStream().write(first, 0, n);
                Thread pump = new Thread(() -> copy(fromClient, quietly(upstream)));
                pump.setDaemon(true);
                pump.start();
                InputStream fromServer = upstream.getInputStream();
                OutputStream toClient = client.getOutputStream();
                if (drop) {
                    // Wait for the server's answer (= it has committed), then
                    // kill the client connection without forwarding it.
                    if (fromServer.read() >= 0) {
                        droppedResponses.incrementAndGet();
                    }
                    return;
                }
                fromServer.transferTo(toClient);
            } catch (IOException ignored) {
                // connection torn down
            }
        }

        private static OutputStream quietly(Socket socket) {
            try {
                return socket.getOutputStream();
            } catch (IOException e) {
                return OutputStream.nullOutputStream();
            }
        }

        private static void copy(InputStream in, OutputStream out) {
            try {
                in.transferTo(out);
            } catch (IOException ignored) {
                // closed
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
