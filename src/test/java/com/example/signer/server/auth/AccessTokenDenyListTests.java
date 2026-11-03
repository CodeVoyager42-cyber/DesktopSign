package com.example.signer.server.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 risk L4: the deny-list itself, without a database and
 * without a server. {@link DbAccessTokenDenyListLiveTests} covers the same
 * rules against PostgreSQL, and
 * {@link com.example.signer.server.security.LogoutDeniesAccessTokenHttpTests}
 * covers what a logout does over HTTP.
 */
class AccessTokenDenyListTests {

    private final TestClock clock = new TestClock();
    private final InMemoryAccessTokenDenyList denied =
            new InMemoryAccessTokenDenyList(clock);

    private Instant in(Duration howLong) {
        return clock.instant().plus(howLong);
    }

    @Test
    void aDeniedTokenIsDeniedAndEveryOtherTokenIsNot() {
        denied.deny("jti-1", in(Duration.ofMinutes(15)));

        assertThat(denied.isDenied("jti-1")).isTrue();
        assertThat(denied.isDenied("jti-2"))
                .as("the list names one token, never a session or an account").isFalse();
        assertThat(denied.isDenied(null)).isFalse();
    }

    @Test
    void denyingTheSameTokenTwiceIsOneEntry() {
        // A client that posts its logout twice, or two instances of it
        // closing the same session, must not be an error or a second row.
        denied.deny("jti-1", in(Duration.ofMinutes(15)));
        denied.deny("jti-1", in(Duration.ofMinutes(15)));

        assertThat(denied.isDenied("jti-1")).isTrue();
        assertThat(denied.size()).isEqualTo(1);
    }

    @Test
    void aTokenThatHasAlreadyExpiredIsNotRecorded() {
        // There is nothing to deny: the decoder refuses it before the
        // deny-list is ever asked. Recording it would be the one case the
        // table's CHECK (expires_at > denied_at) refuses, too.
        denied.deny("expired", clock.instant().minusSeconds(1));
        denied.deny("exactly-now", clock.instant());

        assertThat(denied.size()).isZero();
        assertThat(denied.isDenied("expired")).isFalse();
    }

    @Test
    void anEntryIsDroppedOnceTheTokenItDeniesWouldHaveExpired() {
        denied.deny("short", in(Duration.ofMinutes(15)));
        assertThat(denied.isDenied("short")).isTrue();

        clock.advance(Duration.ofMinutes(16));
        // Writing is what prunes, because writing is the only thing that
        // grows the map. Nothing sweeps it on a timer.
        denied.deny("later", in(Duration.ofMinutes(15)));

        assertThat(denied.size()).isEqualTo(1);
        assertThat(denied.isDenied("later")).isTrue();
        assertThat(denied.isDenied("short"))
                .as("and it was expired anyway — the decoder had already refused it")
                .isFalse();
    }

    @Test
    void nothingIsRecordedForAnAbsentIdOrAnAbsentExpiry() {
        denied.deny(null, in(Duration.ofMinutes(15)));
        denied.deny("", in(Duration.ofMinutes(15)));
        denied.deny("   ", in(Duration.ofMinutes(15)));
        denied.deny("jti-1", null);

        assertThat(denied.size()).isZero();
    }

    @Test
    void anIdTooLongToStoreIsRefusedRatherThanTruncated() {
        // Unreachable for a token this server issued (a 36-character UUID).
        // It is here because truncating would be worse than refusing: the
        // list would hold an id that can never match, while reporting that
        // the token had been revoked.
        String tooLong = "j".repeat(AccessTokenDenyList.MAX_TOKEN_ID_LENGTH + 1);
        denied.deny(tooLong, in(Duration.ofMinutes(15)));

        assertThat(denied.size()).isZero();
        assertThat(denied.isDenied(tooLong)).isFalse();
        assertThat(denied.isDenied(tooLong.substring(0,
                AccessTokenDenyList.MAX_TOKEN_ID_LENGTH)))
                .as("and no truncated form of it is in the list either").isFalse();
    }

    @Test
    void theLengthTheListAcceptsCoversTheIdsJwtServiceIssues() {
        // The bound is on the column, so it has to be comfortably above the
        // only ids that ever reach it.
        assertThat(java.util.UUID.randomUUID().toString().length())
                .isLessThan(AccessTokenDenyList.MAX_TOKEN_ID_LENGTH);
    }

    /* ------------------------------------------------------------------ */

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-22T12:00:00Z");

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
}
