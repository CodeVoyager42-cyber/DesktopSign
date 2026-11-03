package com.example.signer.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * CSRF tokens held <b>server side, in the session</b>, and mirrored into a
 * script-readable {@code XSRF-TOKEN} cookie for the website to echo back
 * (security Phase C).
 *
 * <p>Why not plain {@link org.springframework.security.web.csrf.CookieCsrfTokenRepository}:
 * that stores nothing, so validation only compares the header of a request
 * against the cookie of the same request. Two consequences we do not want:
 *
 * <ul>
 *   <li><b>A token can never be retired.</b> Replaying an old token together
 *       with its old cookie still matches, so rotating the token at sign-in
 *       would be cosmetic.</li>
 *   <li><b>The expected value is client-supplied.</b> Anyone able to set a
 *       cookie on the host — a sibling subdomain over plain HTTP, say — can
 *       choose both halves of the comparison.</li>
 * </ul>
 *
 * <p>Here the session is the authority: the cookie is only a delivery
 * mechanism, and a header is accepted only if it matches the token this
 * session is holding. So a fresh token at sign-in really does retire the old
 * one, and an injected cookie proves nothing.
 *
 * <p>The cookie is deliberately NOT {@code HttpOnly} — the website has to read
 * it. It carries {@code Secure} outside development for the same reason the
 * session cookie does: a CSRF token readable on the wire defeats the check.
 */
public final class SessionBackedCsrfTokenRepository implements CsrfTokenRepository {

    private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();
    private final boolean secureCookie;

    public SessionBackedCsrfTokenRepository(boolean secureCookie) {
        this.secureCookie = secureCookie;
        this.delegate.setHeaderName(WebSecurityConfig.CSRF_HEADER);
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return this.delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request,
                          HttpServletResponse response) {
        this.delegate.saveToken(token, request, response);
        publishCookie(token, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return this.delegate.loadToken(request);
    }

    /** Mirrors the session's token into the readable cookie, or expires it. */
    private void publishCookie(CsrfToken token, HttpServletResponse response) {
        boolean cleared = token == null;
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie
                .from(WebSecurityConfig.CSRF_COOKIE, cleared ? "" : token.getToken())
                .path("/")
                .httpOnly(false)
                .secure(this.secureCookie)
                .sameSite("Lax");
        if (cleared) {
            cookie.maxAge(0);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }
}
