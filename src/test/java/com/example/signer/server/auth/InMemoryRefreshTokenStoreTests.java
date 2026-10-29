package com.example.signer.server.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2: file-mode refresh sessions. */
class InMemoryRefreshTokenStoreTests {

    private final InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore(12);

    @Test
    void rotationReuseAndLogout() {
        String first = store.issue("alice");
        var rotated = (RefreshTokenStore.RefreshResult.Accepted) store.refresh(first);
        String second = rotated.newRefreshToken();
        assertThat(rotated.userId()).isEqualTo("alice");
        assertThat(store.refresh(first)).isInstanceOf(RefreshTokenStore.RefreshResult.ReusedDetected.class);
        assertThat(store.isUsable(second)).isFalse();

        String other = store.issue("alice");
        assertThat(store.revoke(other)).isTrue();
        assertThat(store.revoke(other)).isFalse();
        assertThat(store.refresh(other)).isInstanceOf(RefreshTokenStore.RefreshResult.Invalid.class);
        assertThat(store.refresh("not-a-token")).isInstanceOf(RefreshTokenStore.RefreshResult.Invalid.class);
        assertThat(store.refresh(null)).isInstanceOf(RefreshTokenStore.RefreshResult.Invalid.class);
    }

    @Test
    void logoutAfterRotationEndsTheWholeSignIn() {
        String first = store.issue("bob");
        String second = ((RefreshTokenStore.RefreshResult.Accepted) store.refresh(first)).newRefreshToken();
        assertThat(store.revoke(second)).isTrue();
        assertThat(store.isUsable(second)).isFalse();
    }

    @Test
    void revokeAllForUserLeavesOtherUsersAlone() {
        String a = store.issue("alice");
        String b = store.issue("bob");
        store.revokeAllForUser("alice");
        assertThat(store.isUsable(a)).isFalse();
        assertThat(store.isUsable(b)).isTrue();
    }

    @Test
    void simultaneousRefreshesOfOneTokenGiveExactlyOneNewToken() throws Exception {
        for (int round = 0; round < 50; round++) {
            String token = store.issue("carol");
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<RefreshTokenStore.RefreshResult>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> {
                    start.await();
                    return store.refresh(token);
                });
            }
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<RefreshTokenStore.RefreshResult>> futures = new ArrayList<>();
                for (Callable<RefreshTokenStore.RefreshResult> c : calls) {
                    futures.add(pool.submit(c));
                }
                start.countDown();
                int accepted = 0;
                for (Future<RefreshTokenStore.RefreshResult> f : futures) {
                    if (f.get() instanceof RefreshTokenStore.RefreshResult.Accepted) {
                        accepted++;
                    }
                }
                assertThat(accepted).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void tokensAreRandom64HexAndHashesAreNotTheToken() {
        String token = store.issue("dave");
        assertThat(token).matches("[0-9a-f]{64}");
        assertThat(RefreshTokenStore.hash(token)).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(store.issue("dave")).isNotEqualTo(token);
    }
}
