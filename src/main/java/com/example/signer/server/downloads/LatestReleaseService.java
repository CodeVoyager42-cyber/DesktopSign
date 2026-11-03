package com.example.signer.server.downloads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What the latest GitHub release is, remembered for an hour.
 *
 * <p>This service is the whole rate-limit story of the release feature:
 * however many visitors read the page, this server makes at most one GitHub
 * request per hour, because a page load is answered from the heap and only
 * the cache's expiry asks the source again. Beyond the hour:
 *
 * <ul>
 *   <li>A failed refresh is not fatal. The last good answer keeps serving
 *       — the same guarantee {@code revalidate: 3600} gives, delivered from
 *       the only server this architecture has.</li>
 *   <li>A short cooldown after a failure keeps a broken endpoint from
 *       turning every page load into its own waiting request.</li>
 *   <li>"GitHub answered: no releases exist" is cached exactly like a real
 *       release, so an empty repository is one request, not a storm.</li>
 * </ul>
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LatestReleaseService {

    private static final Logger log = LoggerFactory.getLogger(LatestReleaseService.class);

    /**
     * Long enough to shield a temporarily rate-limited GitHub, short enough
     * that the next reader follows a gap measured in seconds.
     */
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(60);

    /** One answer, and when it was obtained. An empty release is an answer too. */
    private record Entry(Optional<GitHubReleaseClient.Release> release, Instant at) {
    }

    private volatile Entry entry;
    private volatile Instant lastFailure;

    private final GitHubReleaseClient client;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public LatestReleaseService(
            @Value("${signature.remote-server.download.github.api-base-url:https://api.github.com}")
            String apiBase,
            @Value("${signature.remote-server.download.github.repository:CodeVoyager42-cyber/DesktopSign}")
            String repository,
            @Value("${signature.remote-server.download.github.token:}")
            String token,
            @Value("${signature.remote-server.download.github.cache-ttl:PT1H}")
            Duration ttl) {
        this(new GitHubReleaseClient(apiBase, repository, token), ttl, Clock.systemUTC());
    }

    /** Direct construction, so tests can drive the clock and count requests. */
    LatestReleaseService(GitHubReleaseClient client, Duration ttl, Clock clock) {
        this.client = client;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * The latest release: from the cache where it is young, refreshed where
     * it is old, kept where the source fails — never an answer invented.
     */
    public Optional<GitHubReleaseClient.Release> latest() {
        Entry inHand = this.entry;
        Instant now = this.clock.instant();
        if (fresh(inHand, now)) {
            return inHand.release();
        }
        synchronized (this) {
            inHand = this.entry;
            now = this.clock.instant();
            if (fresh(inHand, now)) {
                return inHand.release();
            }
            // A refresh failed a moment ago: do not line up more waiting
            // requests behind it. Whatever is already here is the answer.
            if (this.lastFailure != null
                    && Duration.between(this.lastFailure, now).compareTo(FAILURE_COOLDOWN) < 0) {
                return inHand == null ? Optional.empty() : inHand.release();
            }
            Optional<GitHubReleaseClient.Release> answer;
            try {
                answer = this.client.latest();
            } catch (GitHubReleaseClient.Unavailable e) {
                this.lastFailure = now;
                log.warn("The latest GitHub release could not be refreshed: {}", e.getMessage());
                return inHand == null ? Optional.empty() : inHand.release();
            }
            this.lastFailure = null;
            this.entry = new Entry(answer, now);
            return answer;
        }
    }

    /** The asset published under exactly this file name in the latest release. */
    public Optional<GitHubReleaseClient.Asset> asset(String fileName) {
        return this.latest().flatMap(release -> release.asset(fileName));
    }

    private boolean fresh(Entry entry, Instant now) {
        return entry != null && Duration.between(entry.at(), now).compareTo(this.ttl) < 0;
    }
}
