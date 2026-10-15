package com.example.signer.server.auth;

import com.example.signer.server.api.ApiErrorType;
import com.example.signer.server.invites.InvitationService;
import org.springframework.beans.factory.ObjectProvider;
import com.example.signer.server.auth.JwtService.JwtAuthentication;
import com.example.signer.server.auth.JwtService.JwtValidationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Servlet filter validating {@code Authorization: Bearer <jwt>} on every
 * {@code /api/workflows/**} request. Replaces BasicAuthFilter for the
 * workflow API. The validated subject is stored as a request attribute
 * (the server has no security-context infrastructure) and the controller
 * reads it via {@link AuthController#requireUser(HttpServletRequest)}.
 *
 * <p>On failure the filter writes a structured JSON 401 with the
 * {@code WWW-Authenticate: Bearer} challenge; the response body never
 * distinguishes malformed from expired tokens.
 */
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Request attribute carrying the authenticated user id. */
    public static final String USER_ATTR = "auth.user";
    /**
     * Request attribute set ONLY for guest signing sessions: the
     * {@link JwtService.GuestAuthentication} (workflow, slot, invitation).
     */
    public static final String GUEST_ATTR = "auth.guest";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;
    /** Database mode only (invitations live in PostgreSQL). */
    private final ObjectProvider<
            InvitationService> invitations;

    public JwtAuthFilter(JwtService jwtService,
                         ObjectProvider<
                                 InvitationService> invitations) {
        this.jwtService = jwtService;
        this.invitations = invitations;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/workflows");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response);
            return;
        }
        String token = header.substring(7).trim();
        try {
            JwtAuthentication auth = jwtService.validateAccessJwt(token);
            request.setAttribute(USER_ATTR, auth.subject());
            chain.doFilter(request, response);
        } catch (JwtValidationException e) {
            if (e.reason() == JwtService.RejectionReason.WRONG_TYPE) {
                guest(token, request, response, chain);
                return;
            }
            log.info("JWT rejected ({}): {}", e.reason(), request.getRequestURI());
            unauthorized(response);
        }
    }

    /**
     * Guest signing session: allowed only for the requests listed in
     * {@link GuestAccessPolicy}, on its own workflow, and only while its
     * invitation is still active (a revoke or expiry ends it at once).
     */
    private void guest(String token, HttpServletRequest request,
                       HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        JwtService.GuestAuthentication guest;
        try {
            guest = jwtService.validateGuestJwt(token);
        } catch (JwtValidationException e) {
            log.info("JWT rejected ({}): {}", e.reason(), request.getRequestURI());
            unauthorized(response);
            return;
        }
        if (!GuestAccessPolicy.allows(request.getMethod(), request.getRequestURI(), guest)) {
            log.info("Guest session refused {} {} (outside its signing task)",
                    request.getMethod(), request.getRequestURI());
            forbidden(response, "WORKFLOW_NOT_ACCESSIBLE",
                    "This signing link only gives access to its own signing task");
            return;
        }
        InvitationService service = invitations.getIfAvailable();
        if (service == null) {
            unauthorized(response);
            return;
        }
        try {
            service.requireGuestSessionActive(guest);
        } catch (ApiErrorType.ApiException e) {
            forbidden(response, e.code().name(), e.getMessage());
            return;
        }
        request.setAttribute(USER_ATTR, guest.principal());
        request.setAttribute(GUEST_ATTR, guest);
        chain.doFilter(request, response);
    }

    private void forbidden(HttpServletResponse response, String code, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\""
                + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"signer-workflows\"");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
    }
}
