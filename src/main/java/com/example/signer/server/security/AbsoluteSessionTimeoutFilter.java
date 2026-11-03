package com.example.signer.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Hard ceiling on a website session's lifetime, measured from when it was
 * created at login (security Phase C).
 *
 * <p>The session store only knows an <em>idle</em> timeout: a session used
 * every few minutes would never expire. This filter adds the absolute limit,
 * so a stolen cookie becomes useless after it however busy the thief keeps
 * it.
 *
 * <p>Runs on the {@code /web/**} chain only. An over-age session is
 * invalidated server-side — its row is deleted, not merely ignored — and the
 * request is answered 401 so the website sends the user back to sign in.
 */
public final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(AbsoluteSessionTimeoutFilter.class);

    private final Duration absoluteTimeout;

    public AbsoluteSessionTimeoutFilter(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && isOverAge(session)) {
            // The session id is never logged: it is a live credential.
            log.info("Website session passed its absolute lifetime of {} — invalidated",
                    absoluteTimeout);
            session.invalidate();
            SecurityContextHolder.clearContext();
            JsonSecurityErrors.unauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isOverAge(HttpSession session) {
        long age;
        try {
            age = System.currentTimeMillis() - session.getCreationTime();
        } catch (IllegalStateException alreadyInvalid) {
            return false; // another thread invalidated it; nothing to do
        }
        return age > absoluteTimeout.toMillis();
    }
}
