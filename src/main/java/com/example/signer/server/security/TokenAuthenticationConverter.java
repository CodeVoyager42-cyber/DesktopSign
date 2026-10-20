package com.example.signer.server.security;

import com.example.signer.server.auth.JwtService;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a token that already passed signature, algorithm, issuer, audience
 * and expiry checks into a Spring Security identity, by its {@code typ}
 * claim: {@code access} → account ({@code ROLE_USER}), {@code guest} →
 * guest signing session ({@code ROLE_GUEST}). Any other type is refused
 * with a 401, like an invalid token.
 */
final class TokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            String type = jwt.getClaimAsString(JwtService.TYPE_CLAIM);
            if (JwtService.TYPE_GUEST.equals(type)) {
                return new GuestSessionAuthentication(jwt, JwtService.toGuest(jwt));
            }
            JwtService.JwtAuthentication user = JwtService.toAccess(jwt);
            return new JwtAuthenticationToken(jwt,
                    AuthorityUtils.createAuthorityList(SecurityConfig.ROLE_USER), user.subject());
        } catch (JwtService.JwtValidationException e) {
            throw new InvalidBearerTokenException("Invalid token");
        }
    }
}
