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
}
