package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.RefreshTokenEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Refresh tokens (V6). Every lookup is by token hash, never by value. */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    /**
     * Locks the row ({@code SELECT … FOR UPDATE}) so two refreshes of the
     * same token are serialized: the second sees it already rotated.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshTokenEntity t where t.tokenHash = :hash")
    Optional<RefreshTokenEntity> lockByTokenHash(@Param("hash") String hash);

    Optional<RefreshTokenEntity> findByTokenHash(String hash);

    @Modifying
    @Query("update RefreshTokenEntity t set t.revokedAt = :now"
            + " where t.familyId = :family and t.revokedAt is null")
    int revokeFamily(@Param("family") UUID family, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshTokenEntity t set t.revokedAt = :now"
            + " where t.username = :username and t.revokedAt is null")
    int revokeAllForUser(@Param("username") String username, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
