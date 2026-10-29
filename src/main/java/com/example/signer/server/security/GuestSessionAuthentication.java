package com.example.signer.server.security;

import com.example.signer.server.auth.JwtService.GuestAuthentication;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A guest signing session: an invited signer without an account, limited
 * to one signing task. Carries authority {@code ROLE_GUEST} only — never
 * {@code ROLE_USER} — so every rule that requires an account refuses it.
 */
public final class GuestSessionAuthentication extends JwtAuthenticationToken {

    private final GuestAuthentication guest;

    public GuestSessionAuthentication(Jwt jwt, GuestAuthentication guest) {
        super(jwt, AuthorityUtils.createAuthorityList(SecurityConfig.ROLE_GUEST), guest.principal());
        this.guest = guest;
    }

    public GuestAuthentication guest() {
        return guest;
    }
}
