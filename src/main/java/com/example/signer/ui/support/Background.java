package com.example.signer.ui.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one place the desktop UI runs work off the JavaFX thread.
 *
 * <p>Before this, every screen started its own {@code new Thread(...)}
 * (31 places): unbounded, some non-daemon (a stuck token or network call
 * could keep the app from exiting), and unnamed in a shared way. Now all
 * background work goes through a small bounded pool of daemon threads.
 * Each job keeps a readable thread name while it runs, for logs and
 * thread dumps.
 */
public final class Background {

    private static final Logger log = LoggerFactory.getLogger(Background.class);

    /** Enough for parallel page renders + a network call + a token operation. */
    private static final int THREADS = 6;

    private static final ExecutorService POOL = new ThreadPoolExecutor(
            THREADS, THREADS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            new DaemonFactory());

    static {
        ((ThreadPoolExecutor) POOL).allowCoreThreadTimeOut(true);
    }

    private Background() {
    }

    /**
     * Runs {@code work} (typically a JavaFX {@code Task}) in the background.
     *
     * @param name short purpose label, shown as the thread name while it runs
     */
    public static void run(String name, Runnable work) {
        POOL.execute(() -> {
            Thread thread = Thread.currentThread();
            String previous = thread.getName();
            thread.setName("signflow-" + name);
            try {
                work.run();
            } catch (RuntimeException e) {
                // A JavaFX Task reports its own failures; this only catches
                // plain Runnables, which would otherwise die silently.
                log.error("Background job '{}' failed", name, e);
            } finally {
                thread.setName(previous);
            }
        });
    }

    private static final class DaemonFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "signflow-bg-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
