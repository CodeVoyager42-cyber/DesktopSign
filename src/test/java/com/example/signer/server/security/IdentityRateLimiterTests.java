package com.example.signer.server.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sliding window behind the per-recipient e-mail cap, the per-account
 * token-attempt cap and the sign-up throttle. Time is driven by hand, so
 * nothing here sleeps.
 */
class IdentityRateLimiterTests {

    /** A clock the test moves itself. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T12:00:00Z");

        void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        @Override
        public Instant instant() {
            return this.now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void attemptsUnderTheLimitAreAllowedAndCounted() {
        IdentityRateLimiter limiter =
                new IdentityRateLimiter(3, Duration.ofMinutes(15), new TestClock());

        assertThat(limiter.retryAfterSeconds("ada@example.test")).isZero();
        assertThat(limiter.tryAcquire("ada@example.test")).isTrue();
        assertThat(limiter.tryAcquire("ada@example.test")).isTrue();
        // The third was the last one allowed.
        assertThat(limiter.tryAcquire("ada@example.test")).isFalse();
    }

    @Test
    void overTheLimitItSaysHowLongToWait() {
        TestClock clock = new TestClock();
        IdentityRateLimiter limiter = new IdentityRateLimiter(2, Duration.ofHours(1), clock);
        limiter.tryAcquire("ada@example.test");
        limiter.tryAcquire("ada@example.test");

        long wait = limiter.retryAfterSeconds("ada@example.test");

        assertThat(wait).as("a positive, usable Retry-After")
                .isPositive().isLessThanOrEqualTo(3600);
        // Ten minutes in, the wait is ten minutes shorter.
        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.retryAfterSeconds("ada@example.test"))
                .isLessThanOrEqualTo(wait - 600 + 1);
    }

    @Test
    void theWindowSlidesSoTheBudgetComesBack() {
        TestClock clock = new TestClock();
        IdentityRateLimiter limiter = new IdentityRateLimiter(2, Duration.ofHours(1), clock);
        limiter.tryAcquire("ada@example.test");
        limiter.tryAcquire("ada@example.test");
        assertThat(limiter.tryAcquire("ada@example.test")).isFalse();

        clock.advance(Duration.ofHours(1));

        assertThat(limiter.tryAcquire("ada@example.test"))
                .as("once the window has passed the identity may act again").isTrue();
    }

    @Test
    void identitiesAreCountedSeparatelyAndCaseInsensitively() {
        IdentityRateLimiter limiter =
                new IdentityRateLimiter(1, Duration.ofHours(1), new TestClock());

        assertThat(limiter.tryAcquire("ada@example.test")).isTrue();
        assertThat(limiter.tryAcquire("grace@example.test"))
                .as("another identity has its own budget").isTrue();
        assertThat(limiter.tryAcquire(" ADA@Example.TEST "))
                .as("the same address in another case is the same identity").isFalse();
    }

    @Test
    void nothingToKeyOnIsAllowed() {
        IdentityRateLimiter limiter =
                new IdentityRateLimiter(1, Duration.ofHours(1), new TestClock());

        // The caller's other limits (per address) still apply; this one
        // cannot key on an absent identity, so it does not guess.
        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire("  ")).isTrue();
        assertThat(limiter.retryAfterSeconds(null)).isZero();
    }

    @Test
    void limitersSharingOneStoreDoNotSpendEachOtherSBudget() {
        // Every limiter now counts in the same store, so the bucket
        // namespace is what keeps them apart: the mail cap for an address
        // and the token-attempt cap for the account of the same name must be
        // two budgets, not one.
        RateLimitStore shared = new InMemoryRateLimitStore(new TestClock());
        IdentityRateLimiter mail = new IdentityRateLimiter("mail", 1, Duration.ofHours(1), shared);
        IdentityRateLimiter attempts =
                new IdentityRateLimiter("attempt", 1, Duration.ofHours(1), shared);

        assertThat(mail.tryAcquire("ada@example.test")).isTrue();
        assertThat(mail.tryAcquire("ada@example.test")).isFalse();
        assertThat(attempts.tryAcquire("ada@example.test"))
                .as("a different limiter, so a different budget").isTrue();
    }

    @Test
    void aNamelessLimiterOrOneWithNoStoreIsRefused() {
        // The name IS the namespace, so an empty one would quietly merge two
        // limiters' counts.
        RateLimitStore store = new InMemoryRateLimitStore(new TestClock());
        assertThatThrownBy(() -> new IdentityRateLimiter("  ", 1, Duration.ofHours(1), store))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityRateLimiter("mail", 1, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMeaninglessLimitIsRefusedAtConstruction() {
        // So a misconfigured throttle fails startup instead of silently
        // allowing everything.
        assertThatThrownBy(() -> new IdentityRateLimiter(0, Duration.ofHours(1), new TestClock()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityRateLimiter(1, Duration.ZERO, new TestClock()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new IdentityRateLimiter(1, Duration.ofMinutes(-5), new TestClock()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityRateLimiter(1, null, new TestClock()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
