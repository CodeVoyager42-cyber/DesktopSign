package com.example.signer.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits password and guest-code attempts per client address (security
 * Phases 2 and 3): at most
 * {@code max} requests to the credential endpoints per address within a
 * sliding {@code window}; beyond that the server answers {@code 429} with
 * {@code Retry-After}, before any password is checked.
 *
 * <p>The address is the connection's own remote address. A
 * {@code X-Forwarded-For} header is ignored, because any client can send
 * one; put the server behind a proxy that rewrites the remote address
 * (Tomcat {@code RemoteIpValve}, enabled by
 * {@code server.forward-headers-strategy=native}) if a proxy is used.
 *
 * <p>State is in memory: it is per server instance and resets on restart.
 * Account lockout ({@code DbUserAccounts}) is the second, persistent layer.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    /** Credential endpoints: each request checks a password. */
    static final String LAUNCH_PATH = "/api/desktop/launch";

    static final Set<String> LIMITED_PATHS = Set.of("/api/auth/login", "/api/auth/register",
            // Phase C: the website checks the same passwords against the same
            // accounts, so it shares this address's sign-in budget rather than
            // getting a second one. The filter is a shared singleton bean for
            // exactly that reason (SecurityConfig#loginRateLimitFilter).
            "/web/api/auth/login",
            // Phase B: each exchange tests a launch code (own bucket).
            LAUNCH_PATH);

    /**
     * Guest verification endpoints (security Phase 3): each request sends or
     * checks an e-mailed code, so they share the same per-address limit.
     */
    static final java.util.regex.Pattern GUEST_PATHS = java.util.regex.Pattern.compile(
            "^/api/invitations/[^/]+/(guest-code|guest-session)$");

    /**
     * Account self-service (security Phase D). Own bucket, so a flood of
     * password-reset requests cannot lock an address out of signing in — and
     * the other way round. Each of these either sends an e-mail or tests a
     * one-time token, and each is ALSO limited per recipient by
     * {@link IdentityRateLimiter}: the address limit stops one machine, the
     * identity limit stops a distributed flood mailing one person.
     */
    static final Set<String> ACCOUNT_PATHS = Set.of(
            "/web/api/auth/register",
            "/web/api/auth/verify-email",
            "/web/api/auth/resend-verification",
            "/web/api/auth/password-reset/request",
            "/web/api/auth/password-reset/confirm",
            "/web/api/me/password");

    /** Suffix of the account self-service bucket. */
    private static final String ACCOUNT_BUCKET = "|account";

    /**
     * The CSRF bootstrap (security Phase C risk C9). It is a GET, and it
     * creates a session for an anonymous visitor, so an unlimited flood would
     * fill the session table. Own bucket, and a GET exception to the
     * POST-only rule below.
     */
    static final String CSRF_PATH = "/web/api/csrf";

    /** Suffix of the CSRF bootstrap bucket. */
    private static final String CSRF_BUCKET = "|csrf";

    /** Above this many tracked addresses, idle ones are dropped. */
    private static final int MAX_TRACKED = 10_000;

    private final int max;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public LoginRateLimitFilter(int max, long windowSeconds, Clock clock) {
        if (max < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("rate limit and window must be >= 1");
        }
        this.max = max;
        this.windowMillis = windowSeconds * 1000;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("GET".equals(request.getMethod())) {
            // The only limited GET: the CSRF bootstrap (Phase C risk C9).
            return !CSRF_PATH.equals(uri);
        }
        return !"POST".equals(request.getMethod())
                || !(LIMITED_PATHS.contains(uri) || ACCOUNT_PATHS.contains(uri)
                        || GUEST_PATHS.matcher(uri).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String address = request.getRemoteAddr();
        String key = address == null ? "unknown" : address;
        // Launch exchanges get their own bucket (Phase B remediation): a
        // flood of one kind cannot lock the same address out of the other.
        String uri = request.getRequestURI();
        if (LAUNCH_PATH.equals(uri)) {
            key = key + "|launch";
        } else if (ACCOUNT_PATHS.contains(uri)) {
            key = key + ACCOUNT_BUCKET;
        } else if (CSRF_PATH.equals(uri)) {
            key = key + CSRF_BUCKET;
        }
        long retryAfterSeconds = tryAcquire(key);
        if (retryAfterSeconds > 0) {
            log.warn("Rate limit reached for {} on {}", address, uri);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":"
                    + "\"Too many attempts. Wait a minute, then try again.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 0 when allowed, otherwise the seconds until the next attempt is allowed. */
    long tryAcquire(String address) {
        long now = clock.millis();
        if (attempts.size() > MAX_TRACKED) {
            attempts.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || now - last >= windowMillis;
                }
            });
        }
        Deque<Long> times = attempts.computeIfAbsent(address, a -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                long wait = windowMillis - (now - times.peekFirst());
                return Math.max(1, (wait + 999) / 1000);
            }
            times.addLast(now);
            return 0;
        }
    }
}
