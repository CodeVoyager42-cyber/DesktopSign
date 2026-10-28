package com.example.signer.server.downloads;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache is the feature's whole rate-limit story, so these tests drive
 * the clock by hand: one GitHub request per hour no matter how many readers
 * arrive, a failure answered from the last known release, and a cooldown
 * that keeps a broken source from being asked on every page load.
 */
class LatestReleaseServiceTests {

    private static final GitHubReleaseClient.Release RELEASE = new GitHubReleaseClient.Release(
            "v0.2.1", "0.2.1", Instant.parse("2026-10-28T13:53:14Z"), List.of());

    /** A clock the test moves, because a real hour is a long test. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-28T00:00:00Z");

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }

    /** A GitHubReleaseClient with a script: each call pops the next answer. */
    static final class ScriptedClient extends GitHubReleaseClient {
        private final ArrayDeque<Optional<GitHubReleaseClient.Release>> answers;
        int calls;

        ScriptedClient(Optional<GitHubReleaseClient.Release>... answers) {
            super("https://api.github.invalid", "acme/signflow", "token");
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public Optional<GitHubReleaseClient.Release> latest() {
            this.calls++;
            Optional<GitHubReleaseClient.Release> next = this.answers.poll();
            if (next == null) {
                // What a dead endpoint looks like to the service.
                throw new GitHubReleaseClient.Unavailable("script exhausted", null);
            }
            return next;
        }
    }

    @Test
    void withinTheTtlTheCacheAnswersAndGithubIsAskedOnce() {
        MutableClock clock = new MutableClock();
        ScriptedClient client = new ScriptedClient(Optional.of(RELEASE));
        LatestReleaseService service = new LatestReleaseService(client, Duration.ofHours(1), clock);

        assertThat(service.latest()).contains(RELEASE);
        assertThat(service.latest()).contains(RELEASE);
        clock.advance(Duration.ofMinutes(59));
        assertThat(service.latest()).contains(RELEASE);

        assertThat(client.calls).as("one request per hour, not one per reader").isEqualTo(1);
    }

    @Test
    void pastTheTtlItAsksAgain() {
        MutableClock clock = new MutableClock();
        ScriptedClient client = new ScriptedClient(
                Optional.of(RELEASE), Optional.of(RELEASE));
        LatestReleaseService service = new LatestReleaseService(client, Duration.ofHours(1), clock);

        assertThat(service.latest()).contains(RELEASE);
        clock.advance(Duration.ofHours(2));
        assertThat(service.latest()).contains(RELEASE);

        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void aFailedRefreshKeepsServingTheLastKnownRelease() {
        MutableClock clock = new MutableClock();
        // One good answer, then the source is gone.
        ScriptedClient client = new ScriptedClient(Optional.of(RELEASE));
        LatestReleaseService service = new LatestReleaseService(client, Duration.ofHours(1), clock);

        assertThat(service.latest()).contains(RELEASE);
        clock.advance(Duration.ofHours(2));
        assertThat(service.latest())
                .as("stale beats nothing: the page keeps describing a real release")
                .contains(RELEASE);
        // The cooldown: another reader right away must not queue up another
        // waiting request against a source that just failed.
        assertThat(service.latest()).contains(RELEASE);
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void afterTheCooldownItTriesAgain() {
        MutableClock clock = new MutableClock();
        // One good answer, then the source never answers again: the point
        // is that the cooldown ends and a retry is still attempted.
        ScriptedClient client = new ScriptedClient(Optional.of(RELEASE));
        LatestReleaseService service = new LatestReleaseService(client, Duration.ofHours(1), clock);

        assertThat(service.latest()).contains(RELEASE);   // call 1: good
        clock.advance(Duration.ofHours(2));
        assertThat(service.latest()).contains(RELEASE);   // call 2: fails, stale served
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.latest()).contains(RELEASE);   // cooldown over, call 3

        assertThat(client.calls).isEqualTo(3);
    }

    @Test
    void anEmptyAnswerIsCachedLikeARelease() {
        MutableClock clock = new MutableClock();
        // GitHub answered: no releases exist. That is a fact for the hour,
        // not a failure to hammer.
        ScriptedClient client = new ScriptedClient(Optional.empty());
        LatestReleaseService service = new LatestReleaseService(client, Duration.ofHours(1), clock);

        assertThat(service.latest()).isEmpty();
        assertThat(service.latest()).isEmpty();
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void aColdFailureAnswersNothingWithoutHammeringTheSource() {
        MutableClock clock = new MutableClock();
        ScriptedClient client = new ScriptedClient();   // every call fails
        LatestReleaseService service = new LatestReleaseService(client, Duration.ofHours(1), clock);

        assertThat(service.latest()).isEmpty();
        assertThat(service.latest()).isEmpty();
        assertThat(client.calls)
                .as("the cooldown shields a source that never answers")
                .isEqualTo(1);
    }

    @Test
    void anUnconfiguredDeploymentAsksNothingAndSaysNothing() {
        // The real client with a blank token: the bean every deployment
        // without GITHUB_API_TOKEN gets.
        LatestReleaseService service = new LatestReleaseService(
                new GitHubReleaseClient("https://api.github.invalid", "acme/signflow", ""),
                Duration.ofHours(1), new MutableClock());

        assertThat(service.latest()).isEmpty();
        assertThat(service.asset("SignFlow-0.2.1.msi")).isEmpty();
    }
}
