package com.example.signer.server.db;

import com.example.signer.server.workflow.WorkflowDirectory;
import com.example.signer.server.db.repository.UserRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Database-backed {@link WorkflowDirectory}: display names and emails come
 * from the users table (seeded accounts, invited placeholder accounts and
 * registered signers all live there). File mode has no such store — the
 * fallback leaves the sender shown as their user id.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class DbUserDirectory implements WorkflowDirectory {

    private final UserRepository users;

    public DbUserDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    public Profile profileOf(String userId) {
        if (userId == null) {
            return null;
        }
        return users.findByUsername(userId)
                .map(u -> new Profile(
                        blankSafe(u.getDisplayName(), userId),
                        u.getEmail()))
                .orElse(null);
    }

    private static String blankSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
