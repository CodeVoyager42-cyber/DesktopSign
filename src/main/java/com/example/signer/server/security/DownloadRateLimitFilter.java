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
import java.time.Duration;

/**
 * A per-address limit on the two public download routes:
 * {@code GET /downloads/**} and {@code GET /web/api/download-info}.
 *
 * <p>Both are open on purpose — an invited signer has no account yet, and
 * cannot be asked to make one before installing the application that signs
 * (see {@code DownloadController}). Open and unmetered is a different thing:
 * the installer is the largest object this server will ever hand out, so a
 * loop fetching it is the cheapest way to spend the deployment's bandwidth.
 *
 * <p>The budget is deliberately generous, because the honest cases are
 * noisier than they look: a browser may send a second request after a
 * cancelled save, a reader may download the installer and its checksum, a
 * download manager may open several ranged connections for one file, and a
 * whole office can share one address. It is a ceiling on abuse, not a quota.
 *
 * <p>Counts live in the {@link RateLimitStore} — the shared PostgreSQL table
 * when the deployment has a database, the heap when it does not — so the
 * limit holds across restarts and across instances, like every other limit
 * here. The address is the connection's own remote address; a
 * forwarded-address header is ignored because any client can send one.
 */
public final class DownloadRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(DownloadRateLimitFilter.class);

    private static final String FILES_PREFIX = "/downloads/";

    private static final String INFO_PATH = "/web/api/download-info";

    private final IdentityRateLimiter limiter;

    public DownloadRateLimitFilter(int max, Duration window, RateLimitStore store) {
        this.limiter = new IdentityRateLimiter("download", max, window, store);
    }

    /** Counts on the heap: no database, or a test driving time by hand. */
    public DownloadRateLimitFilter(int max, Duration window, Clock clock) {
        this(max, window, new InMemoryRateLimitStore(clock));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        boolean readOnly = "GET".equals(request.getMethod())
                || "HEAD".equals(request.getMethod());
        return !(readOnly && (path.startsWith(FILES_PREFIX) || INFO_PATH.equals(path)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String address = request.getRemoteAddr();
        long retryAfterSeconds = this.limiter.retryAfterSeconds(
                address == null ? "unknown" : address);
        if (retryAfterSeconds > 0) {
            // The address, never the path: the path is what a reader asked
            // for, and a log line is not the place to keep their input.
            log.warn("Downloads throttled for {}", address);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            response.setContentType("application/json");
            // The same wording as every other 429 here, so it reveals
            // nothing about which limit was reached.
            response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":"
                    + "\"Too many attempts. Wait a minute, then try again.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
