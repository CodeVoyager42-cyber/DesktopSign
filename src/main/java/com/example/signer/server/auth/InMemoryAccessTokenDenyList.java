package com.example.signer.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deny-list on the heap, for a server running without a database.
 * Database mode uses {@link DbAccessTokenDenyList} instead.
 *
 * <p><b>Per instance, and forgotten on restart</b>, which is the residual
 * half of risk L4 and has the same shape as the residual half of D3: behind
 * two servers a logout handled by one does not reach the other, so the token
 * still works there until it expires. A restart is harmless in the other
 * direction — it drops the list, but it also drops every in-memory refresh
 * session, so the tokens that outlive it cannot be refreshed either.
 *
 * <p>Nothing sweeps this map on a timer, because nothing needs to: an entry
 * is only ever added by a sign-out, and every add drops the entries that have
 * expired. A list that is not being written to is a list that is not growing.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "false", matchIfMissing = true)
public class InMemoryAccessTokenDenyList implements AccessTokenDenyList {

    private static final Logger log =
            LoggerFactory.getLogger(InMemoryAccessTokenDenyList.class);

    /** {@code jti} → the {@code exp} of the token it belongs to. */
    private final Map<String, Instant> denied = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAccessTokenDenyList() {
        this(Clock.systemUTC());
    }

    InMemoryAccessTokenDenyList(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.clock = clock;
    }

    /**
     * There is no cap on the number of entries, and that is safe rather than
     * an oversight: every entry requires a <b>validly signed, unexpired</b>
     * access token to have been presented, so the only way to add one is to
     * sign in and sign out again — which the per-address limit already
     * bounds — and every entry is removed within one access-token lifetime.
     * A cap would be worse than no cap, because evicting an entry
     * un-revokes a token, and whoever wanted that could simply sign in
     * repeatedly until the entry they cared about was pushed out.
     */
    @Override
    public void deny(String tokenId, Instant expiresAt) {
        if (tokenId == null || tokenId.isBlank() || expiresAt == null) {
            return;
        }
        if (tokenId.length() > MAX_TOKEN_ID_LENGTH) {
            // Unreachable for a token this server issued (a 36-character
            // UUID). Loud, because the alternative is believing a token was
            // revoked when it was not — see MAX_TOKEN_ID_LENGTH.
            log.warn("Not denying an access token: its id is {} characters, over the {}"
                    + " this list can hold", tokenId.length(), MAX_TOKEN_ID_LENGTH);
            return;
        }
        Instant now = this.clock.instant();
        if (!expiresAt.isAfter(now)) {
            return;   // already dead; the decoder refuses it without help
        }
        this.denied.put(tokenId, expiresAt);
        this.denied.values().removeIf(expiry -> !expiry.isAfter(now));
    }

    @Override
    public boolean isDenied(String tokenId) {
        return tokenId != null && this.denied.containsKey(tokenId);
    }

    /** Test visibility: how many tokens are currently denied. */
    int size() {
        return this.denied.size();
    }
}
