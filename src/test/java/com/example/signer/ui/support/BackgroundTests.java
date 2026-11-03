package com.example.signer.ui.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundTests {

    @Test
    void runsOffTheCallerOnADaemonThreadNamedForTheJob() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        AtomicBoolean daemon = new AtomicBoolean();
        Background.run("unit-test", () -> {
            name.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(name.get()).isEqualTo("signflow-unit-test");
        assertThat(daemon.get()).isTrue();
    }

    @Test
    void aFailingJobDoesNotKillThePool() throws Exception {
        Background.run("boom", () -> {
            throw new IllegalStateException("expected in test");
        });
        CountDownLatch after = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            Background.run("after-" + i, after::countDown);
        }
        assertThat(after.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
