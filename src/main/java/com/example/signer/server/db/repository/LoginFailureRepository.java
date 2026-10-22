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
     * Locks the pair until {@code until} once it reached {@code max}
     * failures, and starts its count again. Returns 1 when it locked. Same
     * shape as {@code UserRepository.lockIfTooManyFailures}, deliberately:
     * one rule, applied at two scopes.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LoginFailureEntity f set f.lockedUntil = :until, f.failedCount = 0"
            + " where f.username = :username and f.clientAddress = :address"
            + " and f.failedCount >= :max")
    int lockIfTooManyFailures(@Param("username") String username,
                              @Param("address") String clientAddress,
                              @Param("max") int max,
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

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from LoginFailureEntity f where f.lastFailureAt < :cutoff")
    int deleteIdleBefore(@Param("cutoff") Instant cutoff);
}
