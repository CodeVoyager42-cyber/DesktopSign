package com.example.signer.server.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sliding window, on the heap: one deque of attempt times per bucket,
 * trimmed to the window on every call.
 *
 * <p>This is the algorithm that {@code LoginRateLimitFilter} and
 * {@code IdentityRateLimiter} each carried a copy of until risk D6 was
 * retired. It now exists once, and both of them call it.
 *
 * <p><b>Per instance, and forgotten on restart.</b> That is the whole of
 * risk D3 and C6, and the reason {@link DbRateLimitStore} exists: behind two
 * servers each cap is effectively doubled, and a restart hands out a fresh
 * budget. This implementation is still the right one — and the only possible
 * one — when the server runs without a database, which it can: the limits are
 * then the best that can be done in a single process, and the persistent
 * account lockout in {@code DbUserAccounts} is what actually bounds password
 * guessing.
 */
public final class InMemoryRateLimitStore implements RateLimitStore {

    /**
     * Above this many tracked buckets, idle ones are dropped. The old
     * per-limiter maps held 10 000 addresses and 50 000 identities; one map
     * now holds all of them, so it is the larger of the two.
     */
    private static final int MAX_TRACKED = 50_000;

    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public InMemoryRateLimitStore(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.clock = clock;
    }

    @Override
    public long retryAfterSeconds(String bucket, int max, Duration window) {
        if (bucket == null || max < 1 || window == null
                || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "bucket is required, max must be >= 1 and window positive");
        }
        long windowMillis = window.toMillis();
        long now = this.clock.millis();
        evictIdle(now, windowMillis);
        Deque<Long> times = this.hits.computeIfAbsent(bucket, b -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                long wait = windowMillis - (now - times.peekFirst());
                // Round up, and never answer "wait 0 seconds" to a refusal.
                return Math.max(1, (wait + 999) / 1000);
            }
            times.addLast(now);
            return 0;
        }
    }

    /**
     * A memory guard, not bookkeeping: buckets idle for longer than the
     * window of the call that tripped the limit are dropped. Buckets of
     * different limiters now share this map and so have different windows,
     * which makes this approximate — a bucket of a longer-windowed limiter
     * can be dropped early. Dropping one only ever gives that bucket its
     * budget back, and it takes 50 000 live buckets to happen at all.
     */
    private void evictIdle(long now, long windowMillis) {
        if (this.hits.size() <= MAX_TRACKED) {
            return;
        }
        this.hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || now - last >= windowMillis;
            }
        });
    }
}
