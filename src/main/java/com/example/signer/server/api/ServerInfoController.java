package com.example.signer.server.api;

import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.auth.AuthController;
import com.example.signer.server.invites.InvitationLinkResolver;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
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
 * <p>Requires an account access token: Spring Security
 * ({@code SecurityConfig}) refuses anonymous callers and guest sessions
 * before this controller runs.
 */
@RestController
@RequestMapping("/api/server-info")
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class ServerInfoController {

    private final InvitationLinkResolver links;

    public ServerInfoController(InvitationLinkResolver links) {
        this.links = links;
    }

    /**
     * Where emailed invitation links point, and whether that is local-only.
     * Authenticated: internal network topology is not for anonymous callers.
     */
    @GetMapping("/invitation-link")
    public InvitationLinkInfo invitationLink(HttpServletRequest request) {
        AuthController.requireUser(request);
        return new InvitationLinkInfo(links.baseUrl(), links.localOnly(),
                links.source());
    }
}
