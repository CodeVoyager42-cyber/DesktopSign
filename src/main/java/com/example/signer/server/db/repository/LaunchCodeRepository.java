package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.LaunchCodeEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Launch codes (V8). Every lookup is by hash, never by code value. */
public interface LaunchCodeRepository extends JpaRepository<LaunchCodeEntity, Long> {

    /**
     * Consumes the code if, and only if, it is still usable — in ONE
     * statement. PostgreSQL row locking makes a concurrent second exchange
     * see {@code consumed_at} already set, so it updates 0 rows.
     *
     * @return 1 when this call consumed the code, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LaunchCodeEntity c set c.consumedAt = :now,"
            + " c.consumedAppVersion = :appVersion, c.consumedDevice = :device"
            + " where c.codeHash = :hash and c.consumedAt is null and c.revokedAt is null"
            + " and c.expiresAt > :now")
    int consume(@Param("hash") String hash, @Param("now") Instant now,
                @Param("appVersion") String appVersion, @Param("device") String device);

    Optional<LaunchCodeEntity> findByCodeHash(String codeHash);

    /** How many codes {@code username} asked for since {@code since} (issue throttle). */
    long countByUsernameAndCreatedAtAfter(String username, Instant since);

    /** Unused codes of the same user and slot: replaced by a new one. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LaunchCodeEntity c set c.revokedAt = :now where c.username = :username"
            + " and c.workflowId = :workflowId and c.signerOrder = :signerOrder"
            + " and c.consumedAt is null and c.revokedAt is null")
    int revokeUnused(@Param("username") String username, @Param("workflowId") Long workflowId,
                     @Param("signerOrder") int signerOrder, @Param("now") Instant now);

    List<LaunchCodeEntity> findTop20ByUsernameOrderByCreatedAtDesc(String username);

    @Modifying
    @Query("delete from LaunchCodeEntity c where c.expiresAt < :cutoff and c.consumedAt is null")
    int deleteUnusedExpiredBefore(@Param("cutoff") Instant cutoff);
}
