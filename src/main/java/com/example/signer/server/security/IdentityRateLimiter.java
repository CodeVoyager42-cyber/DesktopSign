package com.example.signer.server.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Sliding-window limit per <b>identity</b> — an e-mail address or a username
 * — rather than per client address (security Phase D).
 *
 * <p>{@link LoginRateLimitFilter} already limits per address, which stops one
 * machine hammering an endpoint. It does not stop a distributed flood from
 * mailing one person over and over, so the endpoints that SEND e-mail are
 * limited on the recipient too.
 *
 * <p>Keys are the identity, lower-cased and namespaced by the limiter that
 * owns it, never a password or token. Where those counts are kept is the
 * {@link RateLimitStore}'s business: a database when the deployment has one,
 * so the cap holds across instances and restarts (risk D3), a map on the heap
 * when it does not.
 *
 * <p>The window itself is no longer implemented here — that was risk D6, the
 * same algorithm written once in this class and once in the address filter.
 * Both now call the one copy in the store, and what is left here is what is
 * actually particular to an identity: lower-casing it, namespacing it, and
 * declining to guess when there is nothing to key on.
 */
public final class IdentityRateLimiter {

    private final String name;
    private final int max;
    private final Duration window;
    private final RateLimitStore store;

    /**
     * @param name   the bucket namespace, so two limiters sharing a store
     *               cannot count against each other ({@code mail},
     *               {@code attempt}, {@code signup})
     * @param max    attempts allowed per identity within the window
     * @param window how far back the count reaches
     */
    public IdentityRateLimiter(String name, int max, Duration window, RateLimitStore store) {
        if (name == null || name.isBlank() || store == null) {
            throw new IllegalArgumentException("name and store are required");
        }
        if (max < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("max must be >= 1 and window positive");
        }
        this.name = name;
        this.max = max;
        this.window = window;
        this.store = store;
    }

    /**
     * Counts on the heap, with a clock of the caller's choosing. For a
     * context with no database, and for tests that drive time by hand.
     */
    public IdentityRateLimiter(int max, Duration window, Clock clock) {
        this("identity", max, window, new InMemoryRateLimitStore(clock));
    }

    /**
     * Whether an action for {@code identity} is allowed now, counting it when
     * it is.
     *
     * @return true when allowed, false when the identity is over its limit
     */
    public boolean tryAcquire(String identity) {
        return retryAfterSeconds(identity) == 0;
    }

    /**
     * The same decision, but saying <b>how long</b> to wait — so a caller that
     * answers {@code 429} can send an accurate {@code Retry-After} instead of
     * guessing. Same contract as {@code LoginRateLimitFilter.tryAcquire}.
     *
     * @return 0 when the action is allowed (and counted), otherwise the
     *         seconds until the oldest counted attempt leaves the window
     */
    public long retryAfterSeconds(String identity) {
        if (identity == null || identity.isBlank()) {
            return 0; // nothing to key on; the address limit still applies
        }
        String key = this.name + ":" + identity.trim().toLowerCase(Locale.ROOT);
        return this.store.retryAfterSeconds(key, this.max, this.window);
    }
}
