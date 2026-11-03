package com.example.signer.server.security;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Empties the shared rate-limit counters between tests.
 *
 * <p>Needed since the counts moved out of the heap and into
 * {@code rate_limit_hits} (security Phase D1 risk D3). In memory they lived
 * in the application context, so every test class started with empty buckets
 * for free. In the database they outlive the context — and the test classes
 * that enable a database nearly all share one (`signer_test`) and all reach
 * it from {@code 127.0.0.1}, so they now share a single
 * {@code addr:127.0.0.1} budget. One class spending it would make the next
 * class answer {@code 429} to its first request, which is a test-isolation
 * problem and nothing to do with the behaviour under test.
 *
 * <p>That shared budget is the <b>point</b> in production: it is what makes a
 * cap mean the same thing behind two instances as behind one. So the fix
 * belongs here, in the tests, and not in the store.
 *
 * <p>A test that asserts a limit actually fires needs this even more than a
 * test that is merely a victim of one: it can only count on the exact
 * sequence of {@code 429}s it expects if it starts from zero.
 */
public final class RateLimitTestSupport {

    private RateLimitTestSupport() {
    }

    /** Forgets every counted attempt, in every bucket. */
    public static void clear(DataSource dataSource) {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM rate_limit_hits");
        } catch (SQLException e) {
            // Loudly: a test that silently kept a spent budget would fail
            // later, somewhere else, for no visible reason.
            throw new IllegalStateException("could not clear rate_limit_hits", e);
        }
    }
}
