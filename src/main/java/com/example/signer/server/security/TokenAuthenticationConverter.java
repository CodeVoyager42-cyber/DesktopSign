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
 * claim: {@code access} → account ({@code ROLE_USER}), {@code task} →
 * desktop signing session from a launch code ({@code ROLE_TASK}). Any other
 * type is refused with a 401, like an invalid token.
 */
final class TokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            // Two accepted types: a desktop task session from a launch code,
            // and an account access token. `guest` went with the invitation
            // workflow, and nothing names it here — a guest-typed token
            // falls through to toAccess, which throws, and leaves as a 401.
            // That is deliberate: the list of accepted types is the whole
            // decision, so there is no separate reject-list to drift from it.
            if (JwtService.TYPE_TASK.equals(jwt.getClaimAsString(JwtService.TYPE_CLAIM))) {
                return new TaskSessionAuthentication(jwt, JwtService.toTask(jwt));
            }
            JwtService.JwtAuthentication user = JwtService.toAccess(jwt);
            return new JwtAuthenticationToken(jwt,
                    AuthorityUtils.createAuthorityList(SecurityConfig.ROLE_USER), user.subject());
        } catch (JwtService.JwtValidationException e) {
            throw new InvalidBearerTokenException("Invalid token");
        }
    }
}
