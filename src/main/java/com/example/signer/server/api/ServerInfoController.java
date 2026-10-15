package com.example.signer.server.api;

import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.invites.InvitationLinkResolver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only server information for the desktop client.
 *
 * <p>Currently one thing: the base URL that invitation emails are built
 * from. The sender otherwise has no way to know where their recipients are
 * being sent — and a {@code localhost} base produces links that cannot be
 * opened from another machine, which is worth showing rather than
 * discovering when a signer reports a dead link.
 *
 * <p>Authentication is resolved from the Bearer header here rather than from
 * the request attribute set by {@code JwtAuthFilter}, which is scoped to
 * {@code /api/workflows}; this path is deliberately not part of that API.
 */
@RestController
@RequestMapping("/api/server-info")
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class ServerInfoController {

    private final InvitationLinkResolver links;
    private final JwtService jwtService;

    public ServerInfoController(InvitationLinkResolver links,
                                JwtService jwtService) {
        this.links = links;
        this.jwtService = jwtService;
    }

    /**
     * Where emailed invitation links point, and whether that is local-only.
     * Authenticated: internal network topology is not for anonymous callers.
     */
    @GetMapping("/invitation-link")
    public InvitationLinkInfo invitationLink(
            @RequestHeader(value = "Authorization", required = false)
            String header) {
        requireUser(header);
        return new InvitationLinkInfo(links.baseUrl(), links.localOnly(),
                links.source());
    }

    /** Validates the presented access token or fails with a clean 401. */
    private void requireUser(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw unauthorized();
        }
        try {
            jwtService.validateAccessJwt(header.substring(7).trim());
        } catch (JwtService.JwtValidationException e) {
            throw unauthorized();
        }
    }

    private static ApiErrorType.ApiException unauthorized() {
        return new ApiErrorType.ApiException(ApiErrorCode.UNAUTHORIZED,
                "Authentication required");
    }
}
