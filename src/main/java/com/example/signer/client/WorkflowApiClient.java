package com.example.signer.client;

import com.example.signer.config.ProductionSafetyValidator;
import com.example.signer.server.api.ApiContracts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts.ApiError;
import com.example.signer.server.api.ApiContracts.AuditView;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;
import com.example.signer.server.api.ApiContracts.WorkflowView;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * REST client for the coordination server (REMOTE signing mode). Built on the
 * JDK {@link HttpClient} — no extra dependency. Keeps every HTTP concern out
 * of the JavaFX controllers: controllers call {@link RemoteSigningService},
 * which calls this client.
 *
 * <p>Not a Spring bean: instances are bound to one authenticated workflow
 * user and created per session by the remote-workflow dialog.
 *
 * <p>Authentication: JWT Bearer access token obtained from
 * {@code POST /api/auth/login} (replacing the former HTTP Basic). Expired
 * access tokens are refreshed once per request via the rotating refresh
 * token. The token PIN never reaches this class — nothing here touches
 * signing at all.
 */
public class WorkflowApiClient implements SignerTasksApi, WorkflowOwnerApi {

    private static final Logger log = LoggerFactory.getLogger(WorkflowApiClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final String baseUrl;
    private Session session;

    /**
     * One authenticated workflow-user session (JWT + refresh token). The
     * desktop logs in once; access tokens are short-lived and transparently
     * refreshed on 401 by re-presenting the refresh token. The refresh
     * token lives only in memory and is wiped on close.
     */
    public static final class Session implements AutoCloseable {
        private volatile String accessToken;
        private volatile String refreshToken;
        private volatile boolean closed;
        /** Scoped by the server to ONE signing task; no refresh token. */
        private volatile boolean taskScoped;
        /** The ACCOUNT id; replaced by the server's answer via /me. */
        private volatile String username;
        /**
         * Server that issued the tokens: logout must reach it. (Before this
         * was tracked, close() posted the logout to a relative URL, which
         * always failed, so refresh sessions were never revoked server-side.)
         */
        private volatile String serverBaseUrl;

        /** Exchanges username/password for a token pair on the server. */
        public static Session login(String baseUrl, String user, String password) {
            TokenPairResponse tokens = new WorkflowApiClient(baseUrl, null).authLogin(
                    user, password);
            Session s = ofTokens(user, tokens);
            s.bindServer(baseUrl);
            return s;
        }

        void bindServer(String baseUrl) {
            if (serverBaseUrl == null && baseUrl != null && !baseUrl.isBlank()) {
                serverBaseUrl = baseUrl;
            }
        }

        /**
         * A TASK session opened with a website launch code: one access token
         * scoped by the server to a single signing task, no refresh token.
         * When it expires the signer opens the document from the website
         * again.
         */
        static Session ofTask(String signerId, String accessToken) {
            Session s = new Session(signerId);
            s.accessToken = accessToken;
            s.refreshToken = null;
            s.taskScoped = true;
            return s;
        }

        /** True for a single-task session (see {@link #ofTask}). */
        public boolean isTaskScoped() {
            return taskScoped;
        }

        /** Binds an already-issued token pair (e.g. after registration). */
        public static Session ofTokens(String user, TokenPairResponse tokens) {
            Session s = new Session(user);
            s.accessToken = tokens.accessToken();
            s.refreshToken = tokens.refreshToken();
            return s;
        }

        private Session(String username) {
            this.username = username;
        }

        /** The workflow user this session authenticates. */
        public String username() {
            return username;
        }

        /**
         * Adopts the server-validated account id as the session identity.
         * The label a dialog starts from (an invited email, a typed login)
         * is only a hint; the server's answer is authoritative.
         */
        void adoptUsername(String accountId) {
            if (accountId != null && !accountId.isBlank()) {
                this.username = accountId;
            }
        }

        String accessToken() {
            return accessToken;
        }

        String refreshToken() {
            return refreshToken;
        }

        void updateTokens(TokenPairResponse tokens) {
            this.accessToken = tokens.accessToken();
            this.refreshToken = tokens.refreshToken();
        }

        boolean isClosed() {
            return closed;
        }

        /**
         * Local logout after the server refused the refresh token: wipes the
         * secrets without calling the server (the family is already dead).
         */
        void expire() {
            closed = true;
            refreshToken = null;
            accessToken = null;
        }

        /**
         * Revokes the server-side refresh session and wipes local secrets.
         *
         * <p>The access token is sent along with the logout, not merely
         * dropped: the server cannot delete a signed JWT, so it has to be
         * told which one to refuse for the rest of its life (Phase 2 risk
         * L4). Both are read out before the fields are cleared, because
         * wiping them first is what the local half of signing out means.
         */
        @Override
        public void close() {
            closed = true;
            String token = refreshToken;
            String access = accessToken;
            refreshToken = null;
            accessToken = null;
            if (token != null) {
                try {
                    new WorkflowApiClient(serverBaseUrl, null).authLogout(token, access);
                } catch (RuntimeException ignored) {
                    // best-effort: local secrets are wiped regardless
                }
            }
        }
    }

    /** Constructor for {@link Session#login} and auth calls (no session yet). */
    public WorkflowApiClient(String baseUrl, Session session) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.session = session;
        if (session != null) {
            session.bindServer(this.baseUrl);
        }
        if (!this.baseUrl.isEmpty()) {
            requireSecureTransport(this.baseUrl);
        }
    }

    /**
     * Development switch, set ONLY by the desktop application when the
     * {@code dev} profile is active: allows plain {@code http://} to a
     * non-loopback coordination server (e.g. a LAN test server).
     */
    private static volatile boolean allowInsecureTransport = false;

    public static void allowInsecureTransportForDevelopment(boolean allow) {
        allowInsecureTransport = allow;
    }

    /**
     * Transport policy (P0.9): passwords, JWTs, refresh tokens, invitation
     * tokens and PDFs only travel over https. Plain http is accepted for
     * this machine's loopback address (local development server, tests) or
     * when the {@code dev} profile explicitly allowed it. Certificate
     * validation is never disabled: the JDK trust store (or the Windows
     * trust store in packaged builds) decides — see docs/TRANSPORT-SECURITY.md.
     */
    static void requireSecureTransport(String baseUrl) {
        if (allowInsecureTransport
                || ProductionSafetyValidator.isHttpsOrLoopback(baseUrl)) {
            return;
        }
        throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                "Refusing to contact the coordination server over plain HTTP ("
                        + baseUrl + "). Use its https:// address.");
    }

    /**
     * Logs in and returns a client bound to the authenticated session.
     * The password char[] is cleared immediately after use.
     */
    public static WorkflowApiClient login(String baseUrl,
                                          String user,
                                          char[] password) {
        Session session = Session.login(baseUrl, user, new String(password));
        Arrays.fill(password, '\u0000');
        return new WorkflowApiClient(baseUrl, session);
    }

    /**
     * Uploads a PDF to sign (multipart). The signed-in account is its only
     * signer; it registers its certificate when it signs, so no fingerprint
     * is sent here.
     */
    @Override
    public WorkflowView createWorkflow(String documentName, byte[] originalPdf) {
        String boundary = "wf-" + UUID.randomUUID();
        String json;
        try {
            json = mapper.writeValueAsString(new CreateWorkflowRequest(documentName));
        } catch (Exception e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The workflow request could not be built", e);
        }
        byte[] body = multipart(boundary,
                part(boundary, "request", "application/json",
                        json.getBytes(StandardCharsets.UTF_8)),
                filePart(boundary, "file",
                        documentName.endsWith(".pdf") ? documentName : documentName + ".pdf",
                        "application/pdf", originalPdf));
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)),
                WorkflowView.class, 201);
    }

    /** Documents waiting for this user's signature. */
    @Override
    public List<WorkflowView> myTasks() {
        return Arrays.asList(send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/my-tasks"))
                        .GET(),
                WorkflowView[].class, 200));
    }

    /** The coordination server base URL this client talks to. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * The Signing Tasks workspace payload: every workflow the signed-in
     * user participates in, across all lifecycle states, with sender and
     * date metadata. Backs the desktop Signing Tasks screen.
     */
    @Override
    public ApiContracts.SigningTasksView
            signingTasks() {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/signing-tasks"))
                        .GET(),
                ApiContracts.SigningTasksView.class,
                200);
    }

    /** Workflows created by the signed-in user (the inviter's picker). */
    @Override
    public List<WorkflowView> myWorkflows() {
        return Arrays.asList(send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/owned"))
                        .GET(),
                WorkflowView[].class, 200));
    }

    /** The current task in {@code workflowId}. */
    @Override
    public TaskView task(String workflowId) {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId + "/task"))
                        .GET(),
                TaskView.class, 200);
    }

    /** Downloads the current revision bytes. */
    @Override
    public byte[] document(String workflowId) {
        byte[] body = send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId + "/document"))
                        .GET(),
                byte[].class, 200);
        if (body == null || body.length == 0) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The server returned an empty document");
        }
        return body;
    }

    /** Submits a locally signed revision (multipart, idempotent by op id). */
    @Override
    public SignatureSubmissionResponse submitSignature(
            String workflowId, SignatureSubmissionRequest request, byte[] signedPdf) {
        String boundary = "wf-" + UUID.randomUUID();
        String json;
        try {
            json = mapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The submission request could not be built", e);
        }
        byte[] body = multipart(boundary,
                part(boundary, "request", "application/json",
                        json.getBytes(StandardCharsets.UTF_8)),
                filePart(boundary, "file", "signed.pdf", "application/pdf", signedPdf));
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId + "/signatures"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)),
                SignatureSubmissionResponse.class, 200);
    }

    /** Revision history. */
    @Override
    public List<RevisionView> revisions(String workflowId) {
        return Arrays.asList(send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId + "/revisions"))
                        .GET(),
                RevisionView[].class, 200));
    }

    /** Audit trail. */
    @Override
    public List<AuditView> audit(String workflowId) {
        return Arrays.asList(send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId + "/audit"))
                        .GET(),
                AuditView[].class, 200));
    }

    /**
     * The server's authenticated account id for this session. The identity a
     * dialog starts from (an invited email, a typed username or email) is a
     * hint only — the JWT subject is the account, and every workflow lookup
     * must use it.
     */
    public String me() {
        ApiContracts.MeResponse me = send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/auth/me"))
                        .GET(),
                ApiContracts.MeResponse.class, 200);
        return me == null ? null : me.userId();
    }

    /**
     * Resolves and ADOPTS the authenticated account id: after this call the
     * session — and therefore every request path that embeds the user id —
     * uses the account, not the label the dialog started with.
     */
    public String resolveSessionIdentity() {
        String account = me();
        if (session != null) {
            session.adoptUsername(account);
        }
        return account == null && session != null ? session.username() : account;
    }

    /**
     * Registers the signed-in user's own certificate for their document.
     * The target user comes from the session — never from a parameter.
     */
    @Override
    public WorkflowView bindMyCertificate(String workflowId, String certificateSha256) {
        String username = session != null ? session.username() : null;
        if (username == null) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Not signed in — cannot bind a certificate");
        }
        String json;
        try {
            json = mapper.writeValueAsString(
                    new ApiContracts.BindCertificateRequest(
                            certificateSha256));
        } catch (Exception e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The binding request could not be built", e);
        }
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId
                                + "/signers/" + username + "/certificate"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                json.getBytes(StandardCharsets.UTF_8))),
                WorkflowView.class, 200);
    }

    /** Cancels the workflow. */
    @Override
    public WorkflowView cancel(String workflowId) {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId + "/cancel"))
                        .POST(HttpRequest.BodyPublishers.noBody()),
                WorkflowView.class, 200);
    }

    /* ---------------- Phase B: health and website launch codes ---------------- */

    /**
     * Public reachability check ({@code GET /api/health}). Replaces the old
     * "log in as probe/probe", which cost a failed login every time.
     */
    public static ApiContracts.HealthView health(String baseUrl) {
        WorkflowApiClient anonymous = new WorkflowApiClient(baseUrl, null);
        return anonymous.send(HttpRequest.newBuilder()
                        .uri(URI.create(anonymous.baseUrl + "api/health"))
                        .GET(),
                ApiContracts.HealthView.class, 200);
    }

    /**
     * Exchanges a one-time launch code (from a {@code signflow://launch} link)
     * for a task session limited to that one signing task. The code goes in
     * the request BODY, never the URL, and is never logged.
     */
    public static ApiContracts.LaunchSessionView exchangeLaunchCode(String baseUrl, String code,
                                                                    String appVersion,
                                                                    String device) {
        WorkflowApiClient anonymous = new WorkflowApiClient(baseUrl, null);
        String json;
        try {
            json = anonymous.mapper.writeValueAsString(
                    new ApiContracts.LaunchExchangeRequest(code, appVersion, device));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return anonymous.send(HttpRequest.newBuilder()
                        .uri(URI.create(anonymous.baseUrl + "api/desktop/launch"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)),
                ApiContracts.LaunchSessionView.class, 200);
    }

    /**
     * A client acting with a task session from {@link #exchangeLaunchCode}:
     * one signing task, no refresh token, nothing to revoke on close.
     */
    public static WorkflowApiClient forTask(String baseUrl, ApiContracts.LaunchSessionView task) {
        WorkflowApiClient client = new WorkflowApiClient(baseUrl, null);
        client.session = Session.ofTask(task.signerId(), task.accessToken());
        client.session.bindServer(client.baseUrl);
        return client;
    }

    /**
     * Asks for a launch code for one of the signed-in user's signing tasks
     * (what the website will do; also usable by tests and tools).
     */
    public ApiContracts.LaunchCodeIssued issueLaunchCode(String workflowId) {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + urlEncode(workflowId)
                                + "/launch-codes"))
                        .POST(HttpRequest.BodyPublishers.noBody()),
                ApiContracts.LaunchCodeIssued.class, 201);
    }

    /** Reported when the build did not record a version (IDE run, unfiltered build). */
    public static final String UNKNOWN_VERSION = "unknown";

    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile(
            "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(-[0-9A-Za-z]+(\\.[0-9A-Za-z]+)*)?$");

    private static volatile String appVersion;

    /**
     * This app's version, sent when exchanging a launch code: the
     * {@code version} of {@code signflow-build.properties}, which Maven fills
     * in at build time. {@value #UNKNOWN_VERSION} — never a release-looking
     * guess — when the file is missing, unfiltered or malformed. A
     * compatibility signal only: the server cannot verify it.
     */
    public static String appVersion() {
        String cached = appVersion;
        if (cached == null) {
            cached = readBuildVersion(WorkflowApiClient.class
                    .getResourceAsStream("/signflow-build.properties"));
            appVersion = cached;
        }
        return cached;
    }

    /** The validated version in a {@code signflow-build.properties} stream, or {@value #UNKNOWN_VERSION}. */
    static String readBuildVersion(java.io.InputStream in) {
        if (in == null) {
            return UNKNOWN_VERSION;
        }
        try (in) {
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            String v = props.getProperty("version", "").trim();
            return v.length() <= 20 && VERSION.matcher(v).matches() ? v : UNKNOWN_VERSION;
        } catch (java.io.IOException | IllegalArgumentException e) {
            return UNKNOWN_VERSION;
        }
    }

    /** True when this client acts with a single-task session. */
    public boolean isTaskSession() {
        return session != null && session.isTaskScoped();
    }

    /** Builds a client bound to an already-issued token pair. */
    public static WorkflowApiClient withSession(String baseUrl, String username,
                                                TokenPairResponse tokens) {
        WorkflowApiClient client = new WorkflowApiClient(baseUrl, null);
        client.session = Session.ofTokens(username, tokens);
        client.session.bindServer(client.baseUrl);
        return client;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Maps a non-2xx response to the typed client exception. */
    private static SignerException apiErrorOf(HttpResponse<byte[]> response) {
        String code = null;
        String message = null;
        try {
            ApiError error = new ObjectMapper().registerModule(new JavaTimeModule())
                    .readValue(response.body(), ApiError.class);
            code = error.code();
            message = error.message();
        } catch (Exception ignored) {
            // non-JSON body: the HTTP status below provides the message
        }
        return errorFor(response.statusCode(), code, message);
    }

    /* ------------------------------------------------------------------
     * HTTP plumbing
     * ------------------------------------------------------------------ */

    /**
     * Revokes the server-side refresh session (best-effort) and wipes all
     * local token material. The client is unusable afterwards.
     */
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /** Test visibility: the bound session, or {@code null} once closed. */
    Session sessionForTest() {
        return session;
    }

    /* ------------------------------------------------------------------
     * Authentication calls (login / refresh / logout)
     * ------------------------------------------------------------------ */

    private TokenPairResponse authLogin(String user, String password) {
        try {
            String json = mapper.writeValueAsString(new LoginRequest(user, password));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "api/auth/login"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            json.getBytes(StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                        switch (response.statusCode()) {
                            // The server deliberately gives one answer for a wrong
                            // password, an unknown user and a temporarily locked
                            // account, so the message has to cover all three.
                            case 401 -> "Wrong workflow user or password. After several"
                                    + " failed attempts, sign-in is blocked for a few minutes.";
                            case 429 -> "Too many sign-in attempts from this computer."
                                    + " Wait a minute, then try again.";
                            default -> "Login failed (HTTP " + response.statusCode() + ")";
                        });
            }
            return mapper.readValue(response.body(), TokenPairResponse.class);
        } catch (SignerException e) {
            throw e;
        } catch (Exception e) {
            throw transportFailure("Sign-in", e);
        }
    }

    /**
     * @param accessToken the access token to be denied server-side, or
     *        {@code null} when there is none to send — a session whose
     *        refresh was already refused has nothing left worth denying
     */
    private void authLogout(String refreshToken, String accessToken) {
        try {
            String json = mapper.writeValueAsString(new RefreshRequest(refreshToken));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "api/auth/logout"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            json.getBytes(StandardCharsets.UTF_8)));
            if (accessToken != null && !accessToken.isBlank()) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            log.debug("Logout request failed (local secrets are wiped anyway): {}",
                    e.toString());
        }
    }

    /**
     * Upper bound for one request (connect + transfer). Large uploads are
     * bounded by the server's upload limit; without a request timeout a
     * stalled server would hang the dialog forever.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Thrown inside {@link #refreshAfter} when the server refused the
     * refresh token: the session has been closed and the user must sign in.
     */
    static final String SESSION_EXPIRED_MESSAGE =
            "Your session has expired or was revoked. Sign in again.";
    static final String TASK_SESSION_EXPIRED_MESSAGE =
            "Your signing session has ended or was cancelled. Click \"Sign\" again on the"
                    + " SignFlow website to continue.";

    /**
     * Single-flight refresh after a 401 that was produced with
     * {@code rejectedAccessToken}.
     *
     * <p>Serialised on the {@link Session} (one session may be shared by
     * several clients/threads): when another caller has already rotated the
     * pair while we waited, the fresh access token is reused instead of
     * presenting the (now consumed) refresh token a second time — which the
     * server would treat as refresh-token reuse and revoke the family.
     *
     * @return the access token to retry with (never the rejected one)
     * @throws SignerException when the server refuses the refresh token: the
     *         session is closed locally (logout) before the error is thrown
     */
    private String refreshAfter(String rejectedAccessToken) {
        Session current = session;
        if (current == null || current.isClosed()) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID, SESSION_EXPIRED_MESSAGE);
        }
        synchronized (current) {
            if (current.isClosed()) {
                throw new SignerException(ErrorCode.WORKFLOW_INVALID, SESSION_EXPIRED_MESSAGE);
            }
            String latest = current.accessToken();
            if (latest != null && !latest.equals(rejectedAccessToken)) {
                return latest; // another caller already refreshed
            }
            String token = current.refreshToken();
            if (token == null) {
                boolean taskScoped = current.isTaskScoped();
                current.expire();
                throw new SignerException(ErrorCode.WORKFLOW_INVALID, taskScoped
                        ? TASK_SESSION_EXPIRED_MESSAGE : SESSION_EXPIRED_MESSAGE);
            }
            HttpResponse<byte[]> response;
            try {
                String json = mapper.writeValueAsString(new RefreshRequest(token));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/auth/refresh"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                json.getBytes(StandardCharsets.UTF_8)))
                        .build();
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception e) {
                // Transport problem: the session may still be valid, keep it.
                throw transportFailure("Session refresh", e);
            }
            if (response.statusCode() != 200) {
                // Refresh token expired, revoked, reused or account disabled:
                // log out locally — the tokens are worthless now.
                log.info("Session refresh refused (HTTP {}) — signing out",
                        response.statusCode());
                current.expire();
                throw new SignerException(ErrorCode.WORKFLOW_INVALID, SESSION_EXPIRED_MESSAGE);
            }
            try {
                current.updateTokens(mapper.readValue(response.body(), TokenPairResponse.class));
            } catch (IOException e) {
                throw transportFailure("Session refresh", e);
            }
            return current.accessToken();
        }
    }

    /**
     * Sends {@code builder} with exactly ONE Authorization header (set, never
     * appended) and, on 401, retries once with the refreshed access token.
     */
    private HttpResponse<byte[]> exchange(HttpRequest.Builder builder) throws Exception {
        builder.timeout(REQUEST_TIMEOUT);
        Session current = session;
        if (current == null) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        if (current.isClosed()) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID, SESSION_EXPIRED_MESSAGE);
        }
        String accessToken = current.accessToken();
        builder.setHeader("Authorization", "Bearer " + accessToken);
        HttpResponse<byte[]> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 401) {
            return response;
        }
        // Access token expired (or revoked): one single-flight refresh, then
        // ONE retry with the new token. The builder's header is REPLACED.
        String fresh = refreshAfter(accessToken);
        builder.setHeader("Authorization", "Bearer " + fresh);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private <T> T send(HttpRequest.Builder builder, Class<T> type, int expectedStatus) {
        try {
            HttpResponse<byte[]> response = exchange(builder);
            if (response.statusCode() != expectedStatus) {
                throw apiError(response);
            }
            byte[] bytes = response.body();
            if (type == byte[].class) {
                return type.cast(bytes);
            }
            return mapper.readValue(bytes, type);
        } catch (SignerException e) {
            throw e;
        } catch (Exception e) {
            throw transportFailure("The request", e);
        }
    }

    /** Variant of {@link #send} for endpoints that return no body (204). */
    private void sendVoid(HttpRequest.Builder builder, int expectedStatus) {
        try {
            HttpResponse<byte[]> response = exchange(builder);
            if (response.statusCode() != expectedStatus) {
                throw apiError(response);
            }
        } catch (SignerException e) {
            throw e;
        } catch (Exception e) {
            throw transportFailure("The request", e);
        }
    }

    /**
     * Maps a non-2xx response to the typed client exception.
     *
     * <p>The server answers errors with an {@code ApiError} body
     * ({@code code} + {@code message}). Responses without that shape — a
     * container error page, a 413 raised while parsing the multipart body,
     * a proxy's HTML page — leave both fields null, so the HTTP status
     * decides the message. The code is never fed to {@code switch} while
     * null: that NPE ("Cannot invoke String.hashCode() …") used to be
     * wrapped and reported as "the coordination server could not be
     * reached", even though the server had answered.
     */
    private SignerException apiError(HttpResponse<byte[]> response) {
        String code = null;
        String message = null;
        try {
            ApiError error = mapper.readValue(response.body(), ApiError.class);
            code = error.code();
            message = error.message();
        } catch (Exception ignored) {
            // non-JSON body: the HTTP status below provides the message
        }
        return errorFor(response.statusCode(), code, message);
    }

    /** Builds the typed error: the server's message when present, else the status. */
    private static SignerException errorFor(int status, String code, String message) {
        String text = message != null && !message.isBlank()
                ? message
                : defaultMessageFor(status);
        return new RemoteApiException(errorCodeOf(code), text, status, code);
    }

    /** Null-safe mapping of the server's stable error codes. */
    private static ErrorCode errorCodeOf(String code) {
        if (code == null || code.isBlank()) {
            return ErrorCode.WORKFLOW_INVALID;
        }
        return switch (code) {
            case "STALE_REVISION" -> ErrorCode.WORKFLOW_STALE_DOCUMENT;
            case "CERTIFICATE_MISMATCH" -> ErrorCode.WORKFLOW_CERT_MISMATCH;
            case "NOT_YOUR_TURN", "INVALID_WORKFLOW_STATE",
                 "TURN_ALREADY_CLAIMED" -> ErrorCode.WORKFLOW_OUT_OF_TURN;
            case "DOCUMENT_MODIFIED", "INVALID_SIGNATURE" -> ErrorCode.WORKFLOW_TAMPERED;
            case "CERTIFICATE_REQUIRED", "CERTIFICATE_ALREADY_BOUND" ->
                    ErrorCode.WORKFLOW_INVALID;
            default -> ErrorCode.WORKFLOW_INVALID;
        };
    }

    /**
     * Message for an error response whose body carried nothing usable. The
     * server DID answer, so this never claims it was unreachable.
     */
    private static String defaultMessageFor(int status) {
        return switch (status) {
            case 400 -> "The coordination server rejected the request (HTTP 400)."
                    + " Check the workflow details and try again.";
            case 401 -> "Your session is no longer valid. Sign in again.";
            case 403 -> "You are not allowed to perform this action (HTTP 403).";
            case 404 -> "The workflow was not found on the coordination server"
                    + " (HTTP 404).";
            case 409 -> "The workflow changed on the coordination server"
                    + " (HTTP 409). Reload it and try again.";
            case 410 -> "This request has expired (HTTP 410).";
            case 413 -> "The document is larger than the coordination server's"
                    + " upload limit (HTTP 413). Choose a smaller PDF.";
            case 429 -> "Too many requests to the coordination server (HTTP 429)."
                    + " Wait a minute, then try again.";
            default -> status >= 500
                    ? "The coordination server encountered an error while"
                      + " processing the request (HTTP " + status + ")"
                      + " — check the server log for details."
                    : "The coordination server rejected the request (HTTP "
                      + status + ").";
        };
    }

    /**
     * Classifies a failed call. Only genuine transport problems (no
     * connection, timeout, unresolvable host) may claim the server was
     * unreachable; every other failure keeps its own cause, because the
     * coordination server was clearly reached.
     */
    private SignerException transportFailure(String action, Exception e) {
        return transportFailure(baseUrl, action, e);
    }

    /**
     * Whether {@code e} means the request got NO HTTP response (connection
     * refused/reset, timeout): only then may an idempotent retry help. Errors
     * built from a server response never carry an I/O cause.
     */
    public static boolean isTransportFailure(SignerException e) {
        Throwable root = rootCause(e);
        return root instanceof IOException;
    }

    private static SignerException transportFailure(String baseUrl, String action,
                                                    Exception e) {
        String where = baseUrl == null || baseUrl.isBlank() ? "" : " at " + baseUrl;
        // The whole cause chain is searched, not just its deepest link: the
        // JDK's HTTP client wraps the same condition differently per release.
        // A refused connection is ConnectException → ConnectException on
        // JDK 26, but ConnectException → ConnectException →
        // ClosedChannelException on JDK 21, so looking only at the deepest
        // cause reported a raw "ClosedChannelException" to the user there.
        // Timeouts are checked first: they say more about what happened than
        // a ConnectException that may sit deeper in the same chain.
        if (hasCause(e, HttpConnectTimeoutException.class)
                || hasCause(e, HttpTimeoutException.class)) {
            return new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The coordination server" + where
                            + " did not respond in time.", e);
        }
        if (hasCause(e, UnknownHostException.class)) {
            return new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The coordination server address" + where
                            + " could not be resolved.", e);
        }
        if (hasCause(e, ConnectException.class)) {
            return new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Could not connect to the coordination server" + where
                            + ". Make sure it is running, then try again.", e);
        }
        return new SignerException(ErrorCode.WORKFLOW_INVALID,
                action + " failed: " + rootMessage(e), e);
    }

    /** Whether {@code type} appears anywhere in {@code t}'s cause chain. */
    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = rootCause(t);
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** Local body of /api/auth/refresh and /api/auth/logout calls. */
    private record RefreshRequest(String refreshToken) {
    }

    /* ------------------------------------------------------------------
     * minimal multipart construction (JDK HttpClient has no built-in)
     * ------------------------------------------------------------------ */

    private static byte[] multipart(String boundary, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (byte[] part : parts) {
                out.write(part);
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("multipart assembly failed", e);
        }
        return out.toByteArray();
    }

    private static byte[] part(String boundary, String name, String contentType,
                               byte[] content) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return concat(header, content, "\r\n");
    }

    private static byte[] filePart(String boundary, String name, String filename,
                                   String contentType, byte[] content) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return concat(header, content, "\r\n");
    }

    private static byte[] concat(String a, byte[] b, String c) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(a.getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.write(c.getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
