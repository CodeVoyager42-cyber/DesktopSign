package com.example.signer.server.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Server-side website sessions, stored in PostgreSQL (security Phase C).
 *
 * <p>Active only for the coordination server in database mode. Boot's
 * {@code SessionAutoConfiguration} is statically excluded in
 * {@code application.yml}, so this class is the single path to a session
 * store: the desktop context and the file-based server mode never create
 * one, and the schema stays owned by Flyway ({@code V9__web_sessions.sql})
 * rather than being created by Spring Session.
 *
 * <p>The cookie is the website's ONLY credential. It is never accepted on
 * {@code /api/**} (that chain is stateless and never reads a session), and a
 * bearer token is never accepted here — see
 * {@link com.example.signer.server.security.WebSecurityConfig}.
 *
 * <p>Two independent limits apply: the idle timeout is the session's
 * {@code maxInactiveInterval}, enforced by the session store itself, and the
 * absolute timeout is enforced by {@link AbsoluteSessionTimeoutFilter}, which
 * Spring Session has no equivalent of.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
@EnableConfigurationProperties(WebSessionProperties.class)
@EnableJdbcHttpSession
public class WebSessionConfiguration {

    /**
     * The session cookie. {@code HttpOnly} so script cannot read it,
     * {@code SameSite=Lax} so another site cannot send it on a cross-site
     * POST (CSRF defence in depth beside the token), {@code Path=/} and no
     * {@code Domain} so a sibling subdomain can neither read nor set it.
     * {@code Secure} is forced on outside development, which the
     * {@code __Host-} prefix also requires the browser to check.
     */
    @Bean
    public CookieSerializer cookieSerializer(WebSessionProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(properties.getCookieName());
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.isSecureCookie());
        serializer.setSameSite("Lax");
        // Never scope the cookie to a parent domain: with no Domain attribute
        // the browser sends it to this host only, which __Host- also demands.
        serializer.setDomainName(null);
        serializer.setUseBase64Encoding(false);
        return serializer;
    }

    /** Idle timeout: the store expires a session this long after its last use. */
    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> idleTimeoutCustomizer(
            WebSessionProperties properties) {
        return repository -> repository.setDefaultMaxInactiveInterval(
                properties.getIdleTimeout());
    }
}
