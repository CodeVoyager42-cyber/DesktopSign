package com.example.signer.server.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limit per <b>identity</b> — an e-mail address or a username
 * — rather than per client address (security Phase D).
 *
 * <p>{@link LoginRateLimitFilter} already limits per address, which stops one
 * machine hammering an endpoint. It does not stop a distributed flood from
 * mailing one person over and over, so the endpoints that SEND e-mail are
 * limited on the recipient too.
 *
 * <p>Keys are the identity, lower-cased, never a password or token. State is
 * in memory, so it is per server instance and resets on restart — the same
 * limitation the address limiter has, and the reason the one-time tokens
 * themselves are short-lived and single use rather than relying on this.
 *
 * <p>Note: the sliding window is implemented again here rather than shared
 * with {@code LoginRateLimitFilter}, whose own copy is covered by its tests;
 * folding the two together is a tidy-up for later, not a behaviour change to
 * make inside this phase.
 */
public final class IdentityRateLimiter {

    /** Above this many tracked identities, idle ones are dropped. */
    private static final int MAX_TRACKED = 50_000;

    private final int max;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public IdentityRateLimiter(int max, Duration window, Clock clock) {
        if (max < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("max must be >= 1 and window positive");
        }
        this.max = max;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Whether an action for {@code identity} is allowed now, counting it when
     * it is.
     *
     * @return true when allowed, false when the identity is over its limit
     */
    public boolean tryAcquire(String identity) {
        if (identity == null || identity.isBlank()) {
            return true; // nothing to key on; the address limit still applies
        }
        String key = identity.trim().toLowerCase(Locale.ROOT);
        long now = this.clock.millis();
        if (this.attempts.size() > MAX_TRACKED) {
            this.attempts.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || now - last >= this.windowMillis;
                }
            });
        }
        Deque<Long> times = this.attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= this.windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= this.max) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
