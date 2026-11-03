package com.example.signer.server.security;

import java.time.Duration;

/**
 * Where a sliding-window rate limit keeps its counts (security Phase D1 risk
 * D3).
 *
 * <p>Every limit in the application goes through one of these: the
 * per-address limit on credential endpoints
 * ({@link LoginRateLimitFilter}), the sign-up throttle
 * ({@link SignUpThrottleFilter}) and the two per-identity caps in
 * {@code WebAccountService}. There are two implementations and the deployment
 * decides which:
 *
 * <ul>
 *   <li>{@link InMemoryRateLimitStore} — a map on the heap. Per server
 *       instance, and it forgets everything on restart. The only option when
 *       the server runs without a database, which it can.</li>
 *   <li>{@link DbRateLimitStore} — a PostgreSQL table, so several instances
 *       share one budget and a restart does not hand out a fresh one.</li>
 * </ul>
 *
 * <p>This interface is also the answer to risk D6: the sliding window used to
 * be written twice, once in the address filter and once in the identity
 * limiter. It is now written once per <b>store</b>, and every limiter is a
 * thin caller of this one method.
 */
public interface RateLimitStore {

    /**
     * Counts one attempt against {@code bucket} if it is under the limit, and
     * says how long to wait if it is not.
     *
     * <p>Deciding and counting are the same call on purpose: a caller cannot
     * ask whether it may act and then forget to record that it did.
     *
     * @param bucket what is being limited, namespaced by the limiter that
     *               owns it ({@code addr:}, {@code signup:}, {@code mail:},
     *               {@code attempt:}) so that two limiters cannot collide.
     *               Never a password, a token or a token hash — these are
     *               stored, and in the database implementation they outlive
     *               the request
     * @param max    attempts allowed within the window; at least 1
     * @param window how far back the count reaches
     * @return 0 when the attempt is allowed <b>and has been counted</b>,
     *         otherwise the whole seconds until the oldest counted attempt
     *         leaves the window — never 0, so a caller can treat any
     *         non-zero answer as a refusal
     */
    long retryAfterSeconds(String bucket, int max, Duration window);
}
