package com.example.signer.server.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import java.time.Clock;

/**
 * Picks where the rate limits keep their counts (security Phase D1 risk D3).
 *
 * <p>Exactly one {@link RateLimitStore} bean exists in any context, chosen by
 * the same property that decides whether there is a database at all —
 * {@code signature.remote-server.database.enabled}, the condition
 * {@code DbPersistenceConfiguration} uses. The two beans are mutually
 * exclusive conditions rather than a bean plus a fallback: a
 * {@code @ConditionalOnMissingBean} fallback would depend on the order the
 * two definitions happened to be registered in, and this must not be
 * accidental — a context that silently got the in-memory store while running
 * several instances would believe it had limits it does not have.
 *
 * <p>The in-memory store is not a degraded mode to be apologised for: the
 * server can run with no database at all (file-based mode), and
 * {@code SecurityConfig} wires the per-address limiter in that context too, so
 * there has to be a store there. It is simply the best a single process can
 * do.
 */
@Configuration
public class RateLimitStoreConfiguration {

    /**
     * Shared counts in {@code rate_limit_hits} (V11): one budget across every
     * instance, and it survives a restart.
     */
    @Bean
    @ConditionalOnProperty(name = "signature.remote-server.database.enabled",
            havingValue = "true")
    RateLimitStore dbRateLimitStore(DataSource signerDataSource) {
        return new DbRateLimitStore(signerDataSource, Clock.systemUTC());
    }

    /**
     * Counts on the heap, for a server with no database. Per instance, and
     * forgotten on restart — the residual half of risks D3 and C6.
     */
    @Bean
    @ConditionalOnProperty(name = "signature.remote-server.database.enabled",
            havingValue = "false", matchIfMissing = true)
    RateLimitStore inMemoryRateLimitStore() {
        return new InMemoryRateLimitStore(Clock.systemUTC());
    }
}
