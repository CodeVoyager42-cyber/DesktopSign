package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.EmailVerificationTokenEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * E-mail verification tokens (V10). Every lookup is by hash, never by the
 * value that was e-mailed.
 */
public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationTokenEntity, Long> {

    /**
     * Locks the row ({@code SELECT … FOR UPDATE}) so two clicks on the same
     * link are serialized: the second sees it already consumed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EmailVerificationTokenEntity t where t.tokenHash = :hash")
    Optional<EmailVerificationTokenEntity> lockByTokenHash(@Param("hash") String hash);

    /** The newest token of an account, for the resend throttle. */
    Optional<EmailVerificationTokenEntity>
            findFirstByUsernameOrderByCreatedAtDesc(String username);

    /** Retires every live token of an account (a new one supersedes them). */
    @Modifying
    @Query("update EmailVerificationTokenEntity t set t.consumedAt = :now"
            + " where t.username = :username and t.consumedAt is null")
    int consumeAllForUser(@Param("username") String username, @Param("now") Instant now);

    @Modifying
    @Query("delete from EmailVerificationTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
