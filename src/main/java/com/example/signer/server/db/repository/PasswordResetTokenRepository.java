package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.PasswordResetTokenEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Password-reset tokens (V10). Every lookup is by hash, never by the value
 * that was e-mailed.
 */
public interface PasswordResetTokenRepository
        extends JpaRepository<PasswordResetTokenEntity, Long> {

    /**
     * Locks the row ({@code SELECT … FOR UPDATE}) so two submissions of the
     * same reset link are serialized: only the first sets a password.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetTokenEntity t where t.tokenHash = :hash")
    Optional<PasswordResetTokenEntity> lockByTokenHash(@Param("hash") String hash);

    /** The newest token of an account, for the request throttle. */
    Optional<PasswordResetTokenEntity>
            findFirstByUsernameOrderByCreatedAtDesc(String username);

    /**
     * Retires every live token of an account. Used when a new one is issued
     * and when the password changes by another route, so an old reset link
     * cannot be used afterwards.
     */
    @Modifying
    @Query("update PasswordResetTokenEntity t set t.consumedAt = :now"
            + " where t.username = :username and t.consumedAt is null")
    int consumeAllForUser(@Param("username") String username, @Param("now") Instant now);

    @Modifying
    @Query("delete from PasswordResetTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
