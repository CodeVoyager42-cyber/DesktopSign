package com.example.signer.server.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Website session settings, bound from
 * {@code signature.remote-server.web.session.*} (security Phase C).
 *
 * <p>The defaults here are the production ones. The {@code dev} profile
 * overrides the cookie name and the secure flag so the website works over
 * plain {@code http://localhost}, and
 * {@code com.example.signer.config.ProductionSafetyValidator} refuses those
 * development values outside {@code dev}.
 */
@ConfigurationProperties(prefix = "signature.remote-server.web.session")
public class WebSessionProperties {

    /** Cookie-name prefix the browser ties to Secure + Path=/ + no Domain. */
    public static final String HOST_PREFIX = "__Host-";

    private String cookieName = "__Host-SF_SESSION";
    private boolean secureCookie = true;
    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration absoluteTimeout = Duration.ofHours(8);
    private Duration anonymousIdleTimeout = Duration.ofMinutes(5);

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isSecureCookie() {
        return secureCookie;
    }

    public void setSecureCookie(boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    /**
     * Idle timeout of a session that has not signed in (security Phase C risk
     * C9). The CSRF bootstrap creates a session for every visitor, so these
     * expire much sooner than a signed-in one; sign-in promotes the session to
     * {@link #getIdleTimeout()}.
     */
    public Duration getAnonymousIdleTimeout() {
        return anonymousIdleTimeout;
    }

    public void setAnonymousIdleTimeout(Duration anonymousIdleTimeout) {
        this.anonymousIdleTimeout = anonymousIdleTimeout;
    }
}
