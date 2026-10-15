package com.example.signer.server;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upload-limit contract. Spring Boot's multipart default (1 MB) rejected
 * ordinary documents — the reported failure was a 1.2 MB PDF. These tests
 * pin both halves of the fix against a real embedded server:
 *
 * <ul>
 *   <li>a document of that ordinary size is accepted and persisted;</li>
 *   <li>anything above the configured limit is rejected with the
 *       machine-readable {@code ApiError} body and HTTP 413 — not with a
 *       container error page, which is what used to reach the client as an
 *       unreadable response.</li>
 * </ul>
 *
 * The limits are lowered here purely to keep the test fast; production
 * values come from {@code signature.remote.upload.max-bytes}.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
class UploadLimitHttpTests {

    /** Test-sized document limit (production default is 100 MB). */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("wf-upload-store").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        registry.add("spring.servlet.multipart.max-file-size", () -> "2MB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "3MB");
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void ordinarySizedDocumentUploadsAndCreatesTheWorkflow() throws Exception {
        // 1.2 MB — the size of the document in the reported failure.
        byte[] pdf = pdfOfSize(1_200_000);
        HttpResponse<String> created = createWorkflow("ordinary.pdf", pdf);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("workflowId");

        // Persisted: the creator can read it back.
        String workflowId = field(created.body(), "workflowId");
        HttpResponse<String> fetched = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/workflows/" + workflowId))
                        .header("Authorization", "Bearer " + bearer("alice"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).contains("ordinary.pdf");
    }

    @Test
    void oversizedDocumentIsRejectedWithActionable413() throws Exception {
        byte[] tooBig = pdfOfSize((int) MAX_FILE_BYTES + 2 * 1024 * 1024);

        HttpResponse<String> rejected = createWorkflow("too-big.pdf", tooBig);

        assertThat(rejected.statusCode()).isEqualTo(413);
        assertThat(rejected.body())
                .contains("UPLOAD_TOO_LARGE")
                .contains("maximum upload size")
                .contains("MB");
        // No internals leak to the client.
        assertThat(rejected.body())
                .doesNotContain("Exception")
                .doesNotContain("com.example");
    }

    @Test
    void missingDocumentPartReturnsAReadableValidationError() throws Exception {
        String boundary = "upload-limit-" + System.nanoTime();
        String json = "{\"documentName\":\"no-file.pdf\",\"signers\":"
                + "[{\"order\":1,\"userId\":\"alice\"}]}";
        byte[] body = concat(
                part(boundary, "request", "application/json",
                        json.getBytes(StandardCharsets.UTF_8)),
                ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> rejected = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/workflows"))
                        .header("Authorization", "Bearer " + bearer("alice"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body())
                .contains("BAD_REQUEST")
                .contains("PDF file is required");
    }

    /* ------------------------------------------------------------------ */

    /** Multipart create call, exactly as {@code WorkflowApiClient} sends it. */
    private HttpResponse<String> createWorkflow(String fileName, byte[] pdf)
            throws Exception {
        String boundary = "upload-limit-" + System.nanoTime();
        String json = "{\"documentName\":\"" + fileName + "\","
                + "\"signingPolicy\":\"SEQUENTIAL\","
                + "\"signers\":[{\"order\":1,\"userId\":\"alice\"}]}";
        byte[] body = concat(
                part(boundary, "request", "application/json",
                        json.getBytes(StandardCharsets.UTF_8)),
                filePart(boundary, "file", fileName, pdf),
                ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/workflows"))
                        .header("Authorization", "Bearer " + bearer("alice"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String bearer(String user) throws Exception {
        String json = "{\"username\":\"" + user + "\",\"password\":\"" + user + "-pass\"}";
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json,
                                StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        return field(login.body(), "accessToken");
    }

    /** A valid one-page PDF padded with a trailing comment to {@code size}. */
    private static byte[] pdfOfSize(int size) throws Exception {
        byte[] pdf;
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        if (pdf.length >= size) {
            return pdf;
        }
        byte[] padded = Arrays.copyOf(pdf, size);
        Arrays.fill(padded, pdf.length, size, (byte) '\n');
        return padded;
    }

    /** Minimal JSON field reader (no test dependency on the shared mapper). */
    private static String field(String json, String name) {
        String needle = "\"" + name + "\":\"";
        int start = json.indexOf(needle);
        assertThat(start).as("field %s in %s", name, json).isGreaterThanOrEqualTo(0);
        int from = start + needle.length();
        return json.substring(from, json.indexOf('"', from));
    }

    private static byte[] part(String boundary, String name, String contentType,
                               byte[] content) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return concat(header.getBytes(StandardCharsets.UTF_8), content,
                "\r\n".getBytes(StandardCharsets.UTF_8));
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

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
