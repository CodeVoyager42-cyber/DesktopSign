package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.UserEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.util.Optional;

/** User accounts (login, invitation binding, dev seeding). */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    /**
     * The user's row, locked until the surrounding transaction ends
     * ({@code SELECT ... FOR UPDATE}): serializes per-user operations that
     * count then insert, such as issuing launch codes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.username = :username")
    Optional<UserEntity> lockByUsername(@Param("username") String username);

    boolean existsByUsername(String username);

    /**
     * Whether a username is taken ignoring letter case — the rule the V5
     * {@code uq_users_username_ci} index enforces. Use it before creating an
     * account so a case-only clash is a clean "taken", not a constraint error.
     */
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmail(String email);

    /**
     * Counts one failed sign-in, atomically in the database, so parallel
     * attempts cannot lose an increment.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update UserEntity u set u.failedLoginCount = u.failedLoginCount + 1 where u.id = :id")
    int incrementFailedLogins(@Param("id") Long id);

    /**
     * Locks the account until {@code until} once it reached {@code max}
     * failures, and starts the count again. Returns 1 when it locked.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update UserEntity u set u.lockedUntil = :until, u.failedLoginCount = 0"
            + " where u.id = :id and u.failedLoginCount >= :max")
    int lockIfTooManyFailures(@Param("id") Long id, @Param("max") int max,
                              @Param("until") Instant until);

    /** Clears failures and any lock after a successful sign-in. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update UserEntity u set u.failedLoginCount = 0, u.lockedUntil = null where u.id = :id")
    int clearFailedLogins(@Param("id") Long id);

    /**
     * Deletes accounts that were created by website sign-up, never proved
     * their e-mail address and never did anything (security Phase D1 risk
     * D11). Deliberately a <b>native</b> query: this is the one statement in
     * the application that removes a {@code users} row, so every guard is
     * spelled out where it can be read rather than inferred from JPQL.
     *
     * <p>An account is deleted only when <b>all</b> of these hold:
     *
     * <ul>
     *   <li>{@code email_verified_at IS NULL} — the address was never proved;</li>
     *   <li>{@code status = 'ACTIVE'} — which <b>excludes
     *       {@code PENDING_INVITE}</b>, the invitation identities that also
     *       have no verified address and must never be swept, and
     *       {@code DISABLED}, which an operator chose;</li>
     *   <li>{@code last_login_at IS NULL} — it was never signed in to;</li>
     *   <li>it is older than {@code cutoff};</li>
     *   <li>it holds no signer slot, owns no workflow and holds no claimed
     *       turn.</li>
     * </ul>
     *
     * <p>Those last three are also protected by foreign keys that are
     * {@code NO ACTION}, so the database would refuse such a delete anyway;
     * they are repeated here so the sweep skips those rows quietly instead of
     * failing on them. What does cascade with the row is only its own
     * secrets: refresh tokens (V6), launch codes (V8) and the two token
     * tables (V10). {@code audit_events.actor} and
     * {@code document_versions.created_by} are free text by design, so
     * history is never rewritten by this.
     *
     * @param cutoff accounts created before this are eligible
     * @return how many rows were deleted
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            DELETE FROM users u
             WHERE u.email_verified_at IS NULL
               AND u.status = 'ACTIVE'
               AND u.last_login_at IS NULL
               AND u.created_at < :cutoff
               AND NOT EXISTS (SELECT 1 FROM workflow_signers s
                                WHERE s.username = u.username)
               AND NOT EXISTS (SELECT 1 FROM workflows w
                                WHERE w.created_by = u.username)
               AND NOT EXISTS (SELECT 1 FROM workflows w
                                WHERE w.claimed_turn_username = u.username)
            """)
    int deleteNeverVerifiedBefore(@Param("cutoff") Instant cutoff);
}
