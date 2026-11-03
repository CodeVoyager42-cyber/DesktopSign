package com.example.signer.server.security;

import com.example.signer.server.WorkflowServerApplication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase D1 risk D3 (and C6): the rate limits counted in PostgreSQL
 * instead of on the heap.
 *
 * <p>The sliding window itself is covered by {@link IdentityRateLimiterTests}
 * against the in-memory store, so it is not re-tested here. What is tested
 * here is everything the move to the database was FOR, and the three
 * decisions it rests on:
 *
 * <ul>
 *   <li>a second store over the same database — a second server instance, or
 *       the same one after a restart — sees a budget already spent;</li>
 *   <li>concurrent callers cannot all be admitted past the cap, which is
 *       what the per-bucket advisory lock is for;</li>
 *   <li>a counted attempt survives the rollback of the transaction that
 *       counted it, which is what taking its own connection is for.</li>
 * </ul>
 *
 * <p>Time is driven by a clock the test moves, so nothing here sleeps and no
 * row has to be aged in SQL: the window is measured from that clock.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-d3-limits-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + DbRateLimitStoreLiveTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}"
        })
@EnabledIf(value = "com.example.signer.server.security.DbRateLimitStoreLiveTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class DbRateLimitStoreLiveTests {

    static final String DB = "signflow_d3_limits_test";

    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RateLimitStore wiredStore;

    @Autowired
    private PlatformTransactionManager transactions;

    public static boolean freshDatabase() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DB);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ------------------------------ helpers ----------------------------- */

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

    private DbRateLimitStore storeOn(Clock clock) {
        return new DbRateLimitStore(this.dataSource, clock);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(this.dataSource);
    }

    /** A bucket of its own per test, so no two tests share a budget. */
    private static String newBucket() {
        return "addr:10.0.0." + UUID.randomUUID();
    }

    private int rows(String bucket) {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM rate_limit_hits WHERE bucket = ?", Integer.class, bucket);
        return count == null ? 0 : count;
    }

    /* ------------------------------- tests ------------------------------ */

    @Test
    void aDeploymentWithADatabaseGetsTheSharedStore() {
        // The wiring is what makes the fix take effect: an instance that
        // silently got the in-memory store would believe it had limits that
        // its siblings do not share.
        assertThat(this.wiredStore).isInstanceOf(DbRateLimitStore.class);
    }

    @Test
    void attemptsUnderTheLimitAreAllowedAndEachOneIsARow() {
        DbRateLimitStore store = storeOn(new TestClock());
        String bucket = newBucket();

        assertThat(store.retryAfterSeconds(bucket, 3, WINDOW)).isZero();
        assertThat(store.retryAfterSeconds(bucket, 3, WINDOW)).isZero();
        assertThat(store.retryAfterSeconds(bucket, 3, WINDOW)).isZero();

        assertThat(rows(bucket)).as("one row per counted attempt").isEqualTo(3);
        assertThat(store.retryAfterSeconds(bucket, 3, WINDOW))
                .as("the third was the last one allowed").isPositive();
        assertThat(rows(bucket)).as("a refusal counts nothing").isEqualTo(3);
    }

    @Test
    void overTheLimitItSaysHowLongToWaitAndTheWaitShrinks() {
        TestClock clock = new TestClock();
        DbRateLimitStore store = storeOn(clock);
        String bucket = newBucket();
        store.retryAfterSeconds(bucket, 1, WINDOW);

        long wait = store.retryAfterSeconds(bucket, 1, WINDOW);
        assertThat(wait).isPositive().isLessThanOrEqualTo(WINDOW.toSeconds());

        clock.advance(Duration.ofMinutes(4));
        assertThat(store.retryAfterSeconds(bucket, 1, WINDOW))
                .as("four minutes in, the wait is four minutes shorter")
                .isLessThanOrEqualTo(wait - 240 + 1);
    }

    @Test
    void theWindowSlidesAndTheRowsLeaveWithIt() {
        TestClock clock = new TestClock();
        DbRateLimitStore store = storeOn(clock);
        String bucket = newBucket();
        store.retryAfterSeconds(bucket, 1, WINDOW);
        assertThat(store.retryAfterSeconds(bucket, 1, WINDOW)).isPositive();

        clock.advance(WINDOW);

        assertThat(store.retryAfterSeconds(bucket, 1, WINDOW))
                .as("once the window has passed the bucket may act again").isZero();
        assertThat(rows(bucket)).as("the attempt that left the window was pruned")
                .isEqualTo(1);
    }

    @Test
    void bucketsAreCountedSeparately() {
        DbRateLimitStore store = storeOn(new TestClock());
        String one = newBucket();
        String other = newBucket();

        assertThat(store.retryAfterSeconds(one, 1, WINDOW)).isZero();
        assertThat(store.retryAfterSeconds(one, 1, WINDOW)).isPositive();
        assertThat(store.retryAfterSeconds(other, 1, WINDOW))
                .as("another bucket has its own budget").isZero();
    }

    @Test
    void anotherInstanceSeesTheBudgetAlreadySpent() {
        // THE point of the phase: this second store is both the sibling
        // instance behind the same load balancer and the same instance after
        // a restart. In memory each of those got a fresh budget.
        TestClock clock = new TestClock();
        String bucket = newBucket();
        assertThat(storeOn(clock).retryAfterSeconds(bucket, 2, WINDOW)).isZero();
        assertThat(storeOn(clock).retryAfterSeconds(bucket, 2, WINDOW)).isZero();

        assertThat(storeOn(clock).retryAfterSeconds(bucket, 2, WINDOW))
                .as("the cap is 2 across every instance, not 2 each").isPositive();
    }

    @Test
    void concurrentCallersCannotAllBeAdmittedPastTheCap() throws Exception {
        // Without the per-bucket advisory lock every one of these reads the
        // same count of 0 and every one of them is admitted, which turns a
        // cap of 5 into a cap of 5 per concurrent request.
        DbRateLimitStore store = storeOn(new TestClock());
        String bucket = newBucket();
        int max = 5;
        int callers = 24;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> calls = IntStream.range(0, callers)
                    .<Callable<Long>>mapToObj(i ->
                            () -> store.retryAfterSeconds(bucket, max, WINDOW))
                    .toList();
            long admitted = 0;
            for (Future<Long> result : pool.invokeAll(calls)) {
                if (result.get() == 0L) {
                    admitted++;
                }
            }
            assertThat(admitted).as("exactly the cap, however many ran at once")
                    .isEqualTo(max);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(bucket)).isEqualTo(max);
    }

    @Test
    void aCountedAttemptSurvivesTheRollbackOfTheCallerSTransaction() {
        // The per-account token-attempt cap is called inside a transaction
        // that rolls back on a failed attempt. If the count went with it,
        // failures — the attempts worth counting — would never be counted.
        DbRateLimitStore store = storeOn(new TestClock());
        String bucket = newBucket();

        new TransactionTemplate(this.transactions).execute(status -> {
            store.retryAfterSeconds(bucket, 1, WINDOW);
            status.setRollbackOnly();
            return null;
        });

        assertThat(rows(bucket)).as("counted on its own connection, so it stands")
                .isEqualTo(1);
        assertThat(store.retryAfterSeconds(bucket, 1, WINDOW))
                .as("and the budget really is spent").isPositive();
    }

    @Test
    void theSweepDeletesOnlyWhatIsLongDead() {
        TestClock clock = new TestClock();
        DbRateLimitStore store = storeOn(clock);
        String old = newBucket();
        store.retryAfterSeconds(old, 5, WINDOW);

        // A day and a half later, that row is far outside every window in
        // use; a fresh one is not.
        clock.advance(Duration.ofHours(36));
        String fresh = newBucket();
        store.retryAfterSeconds(fresh, 5, WINDOW);

        store.deleteOldHits();

        assertThat(rows(old)).as("a bucket never used again does not keep its row")
                .isZero();
        assertThat(rows(fresh)).as("housekeeping must never be what refuses a request")
                .isEqualTo(1);
    }
}
