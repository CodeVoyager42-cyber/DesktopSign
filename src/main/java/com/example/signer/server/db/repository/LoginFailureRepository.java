package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.LoginFailureEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Failed sign-ins per account and client address (V13, security Phase 2 risk
 * L3). Every read and write names one exact pair.
 */
public interface LoginFailureRepository extends JpaRepository<LoginFailureEntity, Long> {

    Optional<LoginFailureEntity> findByUsernameAndClientAddress(String username,
                                                                String clientAddress);

    /**
     * Counts one failure for the pair, inserting the row if this is its
     * first. Native, for the upsert: read-then-write in Java would lose an
     * increment when two attempts arrive together, which is exactly the case
     * worth counting — and it would race two inserts into the UNIQUE
     * constraint.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO login_failures (username, client_address, failed_count,
                                        first_failure_at, last_failure_at)
            VALUES (:username, :address, 1, :now, :now)
                ON CONFLICT (username, client_address)
                DO UPDATE SET failed_count = login_failures.failed_count + 1,
                              last_failure_at = :now
            """)
    int countFailure(@Param("username") String username,
                     @Param("address") String clientAddress,
                     @Param("now") Instant now);

    /**
     * Refuses this pair until {@code until}. The caller decides how long,
     * because the duration grows with how many times the pair has failed
     * (security Phase 2 risk L3, the progressive delay).
     *
     * <p>Deliberately <b>does not reset {@code failed_count}</b>, which is
     * what {@code UserRepository.lockIfTooManyFailures} does at the account
     * scope and what this method used to do. Resetting it is what made a
     * lock forgiving: when it expired the attacker got a fresh five fast
     * attempts, for ever. The count now survives the lock, so the next lock
     * is longer, and only a successful sign-in ({@link #forget}) or the
     * sweep clears it.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LoginFailureEntity f set f.lockedUntil = :until"
            + " where f.username = :username and f.clientAddress = :address")
    int lockUntil(@Param("username") String username,
                  @Param("address") String clientAddress,
                  @Param("until") Instant until);

    /**
     * Forgets this pair after a successful sign-in. A delete rather than a
     * reset: the pair is clean, and the row is worth nothing.
     *
     * <p>Only this pair. A successful sign-in by the holder must not clear
     * the rows of the addresses that were guessing at them, or every sign-in
     * would hand an attacker five fresh attempts.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from LoginFailureEntity f where f.username = :username"
            + " and f.clientAddress = :address")
    int forget(@Param("username") String username, @Param("address") String clientAddress);

    /**
     * Forgets every address's failures against this account: the per-address
     * half of an unlock (security Phase 2 risk L3, the operator unlock), and
     * of a successful password reset.
     *
     * <p>{@code flushAutomatically} matters here and is not boilerplate. Both
     * callers have just cleared the account-wide lock on the managed
     * {@code users} row, and the reset path has also set the new password
     * hash; a bulk query that ran before those were flushed, and cleared the
     * context after, would discard them. There is deliberately no
     * {@code clearAutomatically}: neither caller holds a
     * {@link LoginFailureEntity}, so there is nothing stale to drop, and
     * clearing the context is the hazard this annotation exists to avoid.
     *
     * @return how many address locks and counts were forgotten
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from LoginFailureEntity f where f.username = :username")
    int forgetAll(@Param("username") String username);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from LoginFailureEntity f where f.lastFailureAt < :cutoff")
    int deleteIdleBefore(@Param("cutoff") Instant cutoff);
}
