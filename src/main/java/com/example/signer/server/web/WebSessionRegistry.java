package com.example.signer.server.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ends every website session of one account (security Phase C risk C8,
 * delivered in Phase D).
 *
 * <p>A password change or reset must not leave older sessions alive: the point
 * of changing a password is usually that someone else may have had it.
 * {@code spring_session.principal_name} is indexed by V9 for exactly this
 * lookup, so it is one query rather than a scan.
 *
 * <p>Sessions are <b>deleted</b>, not flagged, so the cookies that referenced
 * them are worthless on every server instance immediately.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSessionRegistry.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public WebSessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /**
     * Deletes every stored session of {@code username}.
     *
     * @return how many were removed (0 when the account had none)
     */
    public int revokeAll(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        Map<String, ? extends Session> found =
                this.sessions.findByPrincipalName(username.trim());
        found.keySet().forEach(this.sessions::deleteById);
        if (!found.isEmpty()) {
            // The count is safe to log; a session id never is.
            log.info("Revoked {} website session(s) of '{}'", found.size(), username);
        }
        return found.size();
    }

    /**
     * Deletes every session of {@code username} except {@code keepSessionId}.
     * Used by a password change made BY the signed-in user, who should not be
     * signed out of the browser they are using.
     *
     * @return how many were removed
     */
    public int revokeAllExcept(String username, String keepSessionId) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        Map<String, ? extends Session> found =
                this.sessions.findByPrincipalName(username.trim());
        int removed = 0;
        for (String id : found.keySet()) {
            if (!id.equals(keepSessionId)) {
                this.sessions.deleteById(id);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Revoked {} other website session(s) of '{}'", removed, username);
        }
        return removed;
    }
}
