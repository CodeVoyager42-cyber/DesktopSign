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
 * claim: {@code access} → account ({@code ROLE_USER}). That is the only
 * type there is. Any other is refused with a 401, like an invalid token.
 */
final class TokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            // One accepted type, and one refusal. `guest` went with the
            // invitation workflow and `task` with the desktop launch-code
            // path; a token of either type now fails toAccess and leaves as a
            // 401. Nothing has to name a retired type for that to hold, which
            // is the point — the list of things to reject cannot drift out of
            // step with the list of things accepted, because there is only
            // one of the latter.
            JwtService.JwtAuthentication user = JwtService.toAccess(jwt);
            return new JwtAuthenticationToken(jwt,
                    AuthorityUtils.createAuthorityList(SecurityConfig.ROLE_USER), user.subject());
        } catch (JwtService.JwtValidationException e) {
            throw new InvalidBearerTokenException("Invalid token");
        }
    }
}
