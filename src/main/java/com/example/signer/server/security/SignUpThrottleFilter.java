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
 * A tight, long-window limit on <b>sign-up</b> alone (security Phase D1 risk
 * D1): at most {@code max} new accounts per client address per {@code window},
 * beyond which {@code POST /web/api/auth/register} answers {@code 429} with
 * {@code Retry-After}.
 *
 * <p>Sign-up is open — it has to be, since the whole point is that someone
 * with no account can get one — and it writes a {@code users} row before the
 * address has been proved. The existing limits do not bound that:
 *
 * <ul>
 *   <li>{@code LoginRateLimitFilter}'s {@code |account} bucket allows 20
 *       requests a <b>minute</b> per address across all six account routes,
 *       which is right for someone clicking a link twice and far too
 *       generous for creating accounts.</li>
 *   <li>The per-recipient e-mail cap in {@code WebAccountService} is keyed by
 *       the <b>recipient</b>, so a script using a fresh address every time
 *       never comes near it.</li>
 * </ul>
 *
 * <p>So this is a third limit rather than a tightening of either: minutes are
 * the wrong unit for sign-up, and making the shared account bucket this strict
 * would also punish someone retrying a verification link.
 *
 * <p><b>Why not a CAPTCHA.</b> One would mean a third-party service, an
 * outbound call from a server that deliberately makes none, and a page to host
 * the widget — and there is no frontend yet. A throttle needs none of that,
 * and the two are not alternatives in the long run: a CAPTCHA belongs with the
 * sign-up page (D2), on top of this.
 *
 * <p>State is in memory, so it is per instance and resets on restart — the
 * same limitation every other limiter here has, and the reason an unverified
 * account can do nothing until the address is proved
 * ({@code SigningActionPolicy}) and its rows are swept.
 *
 * <p>Like {@code LoginRateLimitFilter}, the address is the connection's own
 * remote address; a forwarded-address header is ignored because any client can
 * send one.
 */
public final class SignUpThrottleFilter extends OncePerRequestFilter {

    /** The one path this filter limits. */
    static final String SIGN_UP_PATH = "/web/api/auth/register";

    private static final Logger log = LoggerFactory.getLogger(SignUpThrottleFilter.class);

    private final IdentityRateLimiter limiter;

    public SignUpThrottleFilter(int max, Duration window, Clock clock) {
        // The limiter refuses a max below 1 or a non-positive window, so a
        // misconfigured throttle fails startup instead of silently allowing
        // everything.
        this.limiter = new IdentityRateLimiter(max, window, clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
                && SIGN_UP_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String address = request.getRemoteAddr();
        long retryAfterSeconds = this.limiter.retryAfterSeconds(
                address == null ? "unknown" : address);
        if (retryAfterSeconds > 0) {
            log.warn("Sign-up throttled for {}", address);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            response.setContentType("application/json");
            // Deliberately the same wording as the other 429s: it must not
            // reveal which limit was hit, nor anything about the address.
            response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":"
                    + "\"Too many attempts. Wait a minute, then try again.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
