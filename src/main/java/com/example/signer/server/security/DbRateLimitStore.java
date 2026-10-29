package com.example.signer.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The sliding window in PostgreSQL (security Phase D1 risk D3, and C6):
 * {@code rate_limit_hits} (V11), one row per counted attempt.
 *
 * <p>This is what makes a cap mean what it says. In memory each cap was per
 * server instance and was forgotten on restart, so behind two instances the
 * effective cap was double and a restart handed out a fresh budget. Here the
 * instances share one budget and it survives a restart.
 *
 * <h2>Its own transaction, always</h2>
 *
 * <p>Every call takes its own connection with autocommit off and commits
 * before returning, instead of joining whatever transaction the caller is in.
 * That is deliberate, and it is half the point of the class: the per-account
 * token-attempt cap is called from inside a transaction that <b>rolls back on
 * a failed attempt</b>, and a counted attempt that vanished with the rollback
 * would mean failures were never counted — exactly the attempts worth
 * counting.
 *
 * <h2>Serialised per bucket</h2>
 *
 * <p>Counting and inserting are two statements, so without a lock several
 * concurrent requests would each read the same count and each be admitted: a
 * cap of 20 would become 20 per concurrent request. There is no bucket row to
 * lock, so the bucket name itself is locked with
 * {@code pg_advisory_xact_lock(hashtext(bucket))}, released when the
 * transaction ends. Two bucket names that collide in {@code hashtext} wait
 * for each other and nothing more.
 *
 * <h2>It fails open</h2>
 *
 * <p>A database that cannot be reached means the attempt is allowed, logged
 * at WARN. A limiter is an availability guard, not an authentication gate:
 * the gate is the account lockout in {@code DbUserAccounts}, which lives in
 * the same database and is persistent, so an outage cannot turn password
 * guessing into something cheap — and every endpoint behind these limits
 * needs that same database to do anything at all, so failing closed would
 * answer {@code 429} to requests that were going to fail regardless.
 */
public final class DbRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(DbRateLimitStore.class);

    /** Serialises one bucket for the rest of the transaction. */
    private static final String LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)";

    /** Drops the rows of this bucket that have left the window. */
    private static final String PRUNE_SQL =
            "DELETE FROM rate_limit_hits WHERE bucket = ? AND hit_at <= ?";

    /** What is left inside the window, and the oldest of it. */
    private static final String COUNT_SQL =
            "SELECT count(*), min(hit_at) FROM rate_limit_hits WHERE bucket = ?";

    private static final String INSERT_SQL =
            "INSERT INTO rate_limit_hits (bucket, hit_at) VALUES (?, ?)";

    /** The sweep below deletes rows older than this, whatever their bucket. */
    private static final Duration SWEEP_RETENTION = Duration.ofDays(1);

    private static final String SWEEP_SQL = "DELETE FROM rate_limit_hits WHERE hit_at <= ?";

    private final DataSource dataSource;
    private final Clock clock;

    public DbRateLimitStore(DataSource dataSource, Clock clock) {
        if (dataSource == null || clock == null) {
            throw new IllegalArgumentException("dataSource and clock are required");
        }
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public long retryAfterSeconds(String bucket, int max, Duration window) {
        if (bucket == null || max < 1 || window == null
                || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "bucket is required, max must be >= 1 and window positive");
        }
        Instant now = this.clock.instant();
        try {
            return countAndMaybeAdmit(bucket, max, window, now);
        } catch (SQLException e) {
            // Fail open, and say so. The class of failure, never the bucket:
            // it holds a client address or an e-mail address.
            log.warn("Rate-limit store unreachable — attempt allowed uncounted ({})",
                    e.getClass().getSimpleName());
            return 0;
        }
    }

    private long countAndMaybeAdmit(String bucket, int max, Duration window, Instant now)
            throws SQLException {
        try (Connection c = this.dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                long retryAfter = inTransaction(c, bucket, max, window, now);
                c.commit();
                return retryAfter;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    private long inTransaction(Connection c, String bucket, int max,
                               Duration window, Instant now) throws SQLException {
        lock(c, bucket);
        prune(c, bucket, now.minus(window));
        try (PreparedStatement ps = c.prepareStatement(COUNT_SQL)) {
            ps.setString(1, bucket);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long inWindow = rs.getLong(1);
                OffsetDateTime oldest = rs.getObject(2, OffsetDateTime.class);
                if (inWindow >= max && oldest != null) {
                    return waitSeconds(oldest.toInstant(), window, now);
                }
            }
        }
        insert(c, bucket, now);
        return 0;
    }

    private static void lock(Connection c, String bucket) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(LOCK_SQL)) {
            ps.setString(1, bucket);
            ps.execute();
        }
    }

    private static void prune(Connection c, String bucket, Instant windowStart)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PRUNE_SQL)) {
            ps.setString(1, bucket);
            ps.setObject(2, OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    private static void insert(Connection c, String bucket, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            ps.setString(1, bucket);
            ps.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    /**
     * When the oldest attempt still inside the window leaves it. Rounded up,
     * and never 0 — a refusal that said "wait 0 seconds" would be read as an
     * allowance.
     */
    private static long waitSeconds(Instant oldest, Duration window, Instant now) {
        long millis = oldest.plus(window).toEpochMilli() - now.toEpochMilli();
        return Math.max(1, (millis + 999) / 1000);
    }

    /**
     * Deletes rows left behind by buckets that were never used again. A
     * bucket is otherwise pruned only by its own next request, so an address
     * that knocks once and never returns would keep its row for ever.
     *
     * <p>Hourly, with a retention far longer than any window in use, so this
     * is housekeeping and can never be the thing that refuses a request. The
     * initial delay is offset from the other sweeps so they do not all run at
     * once on start-up.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 1_500_000)
    public void deleteOldHits() {
        Instant cutoff = this.clock.instant().minus(SWEEP_RETENTION);
        try (Connection c = this.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SWEEP_SQL)) {
            ps.setObject(1, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Rate-limit sweep: {} counter row(s) older than {} deleted",
                        deleted, SWEEP_RETENTION);
            }
        } catch (SQLException e) {
            log.warn("Rate-limit sweep failed ({})", e.getClass().getSimpleName());
        }
    }
}
