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
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
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
        private volatile boolean guest;
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
         * A GUEST signing session (invited signer, no account): one access
         * token scoped by the server to a single signing task, no refresh
         * token. When it expires the signer re-opens the invitation link.
         */
        static Session ofGuest(String signerId, String accessToken) {
            Session s = new Session(signerId);
            s.accessToken = accessToken;
            s.refreshToken = null;
            s.guest = true;
            return s;
        }

        /** True for a guest signing session (see {@link #ofGuest}). */
        public boolean isGuest() {
            return guest;
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

        /** Revokes the server-side refresh session and wipes local secrets. */
        @Override
        public void close() {
            closed = true;
            String token = refreshToken;
            refreshToken = null;
            accessToken = null;
            if (token != null) {
                try {
                    new WorkflowApiClient(serverBaseUrl, null).authLogout(token);
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

    /** Creates a sequential workflow from the original PDF bytes (multipart). */
    @Override
    public CreateWorkflowResponse createWorkflow(String documentName,
                                       List<CreateWorkflowRequest.SignerSpec> signers,
                                       byte[] originalPdf) {
        return createWorkflow(documentName, null, signers, originalPdf);
    }

    /**
     * Creates a workflow with an explicit signing policy (§11 of the task
     * spec): {@code SEQUENTIAL} (fixed order) or {@code FLEXIBLE} (any
     * pending signer may claim the turn). Signer fingerprints may be null — 
     * those signers then bind their own certificate from their own machine.
     *
     * <p>The response carries the workflow plus the exact invitation links
     * that were emailed to its email-addressed slots. Those links are
     * returned here once and are not retrievable afterwards (the server
     * stores only their token hashes), so a caller that wants to show the
     * sender what went out must read them now.
     */
    @Override
    public CreateWorkflowResponse createWorkflow(String documentName,
                                       String signingPolicy,
                                       List<CreateWorkflowRequest.SignerSpec> signers,
                                       byte[] originalPdf) {
        String boundary = "wf-" + UUID.randomUUID();
        String json;
        try {
            json = mapper.writeValueAsString(
                    new CreateWorkflowRequest(documentName, signingPolicy, signers));
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
                CreateWorkflowResponse.class, 201);
    }

    /** Workflows waiting for this user's signature. */
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
     * Claims the flexible signing turn (§33). Returns the fresh task view on
     * success; a 409 TURN_ALREADY_CLAIMED surfaces as a typed error when
     * another signer holds the turn.
     */
    @Override
    public TaskView claimTurn(String workflowId) {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId + "/claim"))
                        .POST(HttpRequest.BodyPublishers.noBody()),
                TaskView.class, 200);
    }

    /** Who may currently hold the turn. */
    @Override
    public List<String> eligibleSigners(String workflowId) {
        return Arrays.asList(send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/" + workflowId
                                + "/eligible-signers"))
                        .GET(),
                String[].class, 200));
    }

    /**
     * Binds the signed-in user's own certificate to their unbound signer slot
     * (§14). The target user comes from the session — never from a parameter.
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

    /* ------------------------------------------------------------------
     * Invitations (brief §43: invitation acceptance UI support)
     * ------------------------------------------------------------------ */

    /**
     * Public invitation preview: what the invited signer sees before
     * accepting (document, inviter, invited email, expiry). No session
     * needed — the raw token IS the capability.
     */
    public static ApiContracts.InvitationPreviewView
            invitationPreview(String baseUrl, String rawToken) {
        return new WorkflowApiClient(baseUrl, null).send(
                HttpRequest.newBuilder()
                        .uri(URI.create(new WorkflowApiClient(baseUrl, null).baseUrl
                                + "api/invitations/" + urlEncode(rawToken)))
                        .GET(),
                ApiContracts.InvitationPreviewView.class,
                200);
    }

    /**
     * Opens a GUEST signing session from an invitation token: no account,
     * no password, no credentials of any kind are sent — the server
     * validates the token and answers with an access token scoped to this
     * one signing task. The token in the link is a bearer credential: it is
     * never logged here.
     */
    public static ApiContracts.GuestSessionView
            openGuestSession(String baseUrl, String rawToken) {
        WorkflowApiClient anonymous = new WorkflowApiClient(baseUrl, null);
        return anonymous.send(HttpRequest.newBuilder()
                        .uri(URI.create(anonymous.baseUrl + "api/invitations/"
                                + urlEncode(rawToken) + "/guest-session"))
                        .POST(HttpRequest.BodyPublishers.noBody()),
                ApiContracts.GuestSessionView.class, 200);
    }

    /** A client acting with a guest signing session (see {@link #openGuestSession}). */
    public static WorkflowApiClient forGuest(String baseUrl,
            ApiContracts.GuestSessionView guest) {
        WorkflowApiClient client = new WorkflowApiClient(baseUrl, null);
        client.session = Session.ofGuest(guest.signerId(), guest.accessToken());
        client.session.bindServer(client.baseUrl);
        return client;
    }

    /** True when this client acts with a guest signing session. */
    public boolean isGuestSession() {
        return session != null && session.isGuest();
    }

    /** Accepts the invitation with the authenticated session. */
    public ApiContracts.InvitationPreviewView
            acceptInvitation(String rawToken) {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/invitations/" + urlEncode(rawToken)
                                + "/accept"))
                        .POST(HttpRequest.BodyPublishers.noBody()),
                ApiContracts.InvitationPreviewView.class,
                200);
    }

    /**
     * Claims the invitation as the signed-in participant (brief §46): the
     * backend validates token, expiry, revocation, workflow state and
     * identity, then returns the participant-scoped signing context. The
     * server — never the client — decides whether this identity may act as
     * the recipient (brief §19/§81).
     */
    public ApiContracts.SigningSessionView
            claimInvitation(String rawToken) {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/invitations/" + urlEncode(rawToken)
                                + "/claim"))
                        .POST(HttpRequest.BodyPublishers.noBody()),
                ApiContracts.SigningSessionView.class,
                200);
    }

    /**
     * Creates an invitation email for one signer slot of a workflow the
     * signed-in user participates in. Authenticated via the Bearer session
     * exactly like every other API call; the email is delivered by the
     * {@code EmailService} and carries the deep link. The raw token is
     * never returned by the API — only its hash is stored server-side.
     */
    @Override
    public ApiContracts.InvitationView invite(
            String workflowId, int signerOrder, String email) {
        try {
            String json = mapper.writeValueAsString(
                    new ApiContracts.CreateInvitationRequest(
                            signerOrder, email));
            return send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "api/workflows/"
                                    + urlEncode(workflowId) + "/invitations"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    json.getBytes(StandardCharsets.UTF_8))),
                    ApiContracts.InvitationView.class,
                    201);
        } catch (SignerException e) {
            throw e;
        } catch (Exception e) {
            throw transportFailure("The invite request", e);
        }
    }

    /**
     * Owner view of per-slot invitation status (workflow tracking): status,
     * sent/accepted timestamps, slot completion. Never contains raw tokens.
     */
    @Override
    public List<ApiContracts.InvitationStatusView>
            workflowInvitations(String workflowId) {
        return Arrays.asList(send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/workflows/"
                                + urlEncode(workflowId) + "/invitations"))
                        .GET(),
                ApiContracts.InvitationStatusView[].class,
                200));
    }

    /**
     * Owner operation: invalidates the emailed link and sends a fresh one.
     * The server re-checks ownership and invitation state.
     *
     * @return the freshly emailed link, so the sender can see what went out
     */
    @Override
    public InvitationLinkView resendInvitation(long invitationId) {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/invitations/"
                                + invitationId + "/resend"))
                        .POST(HttpRequest.BodyPublishers.noBody()),
                InvitationLinkView.class, 200);
    }

    /**
     * Base URL that emailed invitation links use, as resolved by the server,
     * plus whether it is loopback-only (recipients elsewhere cannot open it).
     */
    @Override
    public InvitationLinkInfo invitationLinkInfo() {
        return send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "api/server-info/invitation-link"))
                        .GET(),
                InvitationLinkInfo.class, 200);
    }

    /**
     * Invitation-bound registration: activates the invited account and
     * returns the token pair of the new session. The password travels only
     * over this request and is cleared by the caller.
     */
    public static ApiContracts.TokenPairResponse
            register(String baseUrl, String invitationToken,
                     String displayName, char[] password) {
        try {
            ObjectMapper mapper2 = new ObjectMapper()
                    .registerModule(new JavaTimeModule());
            String json = mapper2.writeValueAsString(
                    new ApiContracts.RegisterRequest(
                            invitationToken, displayName,
                            new String(password)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(new WorkflowApiClient(baseUrl, null).baseUrl
                            + "api/auth/register"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            json.getBytes(StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<byte[]> response =
                    new WorkflowApiClient(baseUrl, null).http
                            .send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw apiErrorOf(response);
            }                    return mapper2.readValue(response.body(),
                            ApiContracts.TokenPairResponse.class);
        } catch (SignerException e) {
            throw e;
        } catch (Exception e) {
            throw transportFailure(baseUrl, "Registration", e);
        }
    }

    /** Builds a client bound to an already-issued token pair (e.g. after register). */
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

    private void authLogout(String refreshToken) {
        try {
            String json = mapper.writeValueAsString(new RefreshRequest(refreshToken));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "api/auth/logout"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            json.getBytes(StandardCharsets.UTF_8)))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
    static final String GUEST_SESSION_EXPIRED_MESSAGE =
            "Your signing session has ended. Open the invitation link again to continue.";

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
                boolean guest = current.isGuest();
                current.expire();
                throw new SignerException(ErrorCode.WORKFLOW_INVALID, guest
                        ? GUEST_SESSION_EXPIRED_MESSAGE : SESSION_EXPIRED_MESSAGE);
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
        Throwable root = rootCause(e);
        String where = baseUrl == null || baseUrl.isBlank() ? "" : " at " + baseUrl;
        if (root instanceof ConnectException) {
            return new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Could not connect to the coordination server" + where
                            + ". Make sure it is running, then try again.", e);
        }
        if (root instanceof HttpConnectTimeoutException
                || root instanceof HttpTimeoutException) {
            return new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The coordination server" + where
                            + " did not respond in time.", e);
        }
        if (root instanceof UnknownHostException) {
            return new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The coordination server address" + where
                            + " could not be resolved.", e);
        }
        return new SignerException(ErrorCode.WORKFLOW_INVALID,
                action + " failed: " + rootMessage(e), e);
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
