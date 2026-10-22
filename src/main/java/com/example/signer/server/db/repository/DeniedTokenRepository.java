package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.DeniedTokenEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * The access-token deny-list (V12). Every lookup is by {@code jti}, which is
 * the table's unique column, so the per-request check is one index hit.
 */
public interface DeniedTokenRepository extends JpaRepository<DeniedTokenEntity, Long> {

    /**
     * Adds {@code tokenId} to the list, or does nothing if it is already
     * there. Native, for {@code ON CONFLICT DO NOTHING}: signing out twice
     * with the same token has to be a no-op rather than a unique-constraint
     * violation, and deciding that in Java would be a read followed by a
     * write — two concurrent logouts of the same token would both read
     * "absent" and one would then fail.
     */
    @Modifying
    @Query(value = "INSERT INTO denied_access_tokens (token_id, denied_at, expires_at)"
            + " VALUES (:tokenId, :deniedAt, :expiresAt)"
            + " ON CONFLICT (token_id) DO NOTHING", nativeQuery = true)
    int deny(@Param("tokenId") String tokenId,
             @Param("deniedAt") Instant deniedAt,
             @Param("expiresAt") Instant expiresAt);

    boolean existsByTokenId(String tokenId);

    @Modifying
    @Query("delete from DeniedTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
