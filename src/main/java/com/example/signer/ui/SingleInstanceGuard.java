package com.example.signer.ui;

import com.sun.net.httpserver.HttpServer;

import javafx.application.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Only one SignFlow runs at a time.
 *
 * <p>A second launch would otherwise stack an invisible duplicate window and
 * lock the build output. The first instance holds an OS file lock (released
 * automatically when its process dies) and listens on a localhost-only port;
 * a second launch pings that port — forwarding its invitation link, if any —
 * so the first instance raises its window, then the second one exits.
 *
 * <p>No UI here: what "raise the window" means is supplied by the caller
 * ({@link ActivationHandler}).
 */
final class SingleInstanceGuard {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    /** Localhost-only port for second-launch window activation. */
    private static final int ACTIVATION_PORT = 61234;

    /** What {@link #acquire} found. */
    enum Outcome {
        /** This process is the running SignFlow. */
        FIRST_INSTANCE,
        /** Another copy is running and raised its window: exit quietly. */
        HANDED_OFF,
        /** Another copy holds the lock but did not answer (stuck). */
        OTHER_UNRESPONSIVE
    }

    /**
     * Runs on the JavaFX thread when a second launch asks to be shown.
     *
     * @param forwardedInvitationToken token from the link the user clicked, or null
     * @return true when a window was brought to the front
     */
    @FunctionalInterface
    interface ActivationHandler {
        boolean activate(String forwardedInvitationToken);
    }

    private FileChannel lockChannel;
    private FileLock lock;
    private HttpServer activationServer;

    /**
     * Tries to become the running instance; if another copy runs, asks it
     * to show its window (and open {@code invitationToken}, when given).
     */
    Outcome acquire(String invitationToken) {
        try {
            lockChannel = FileChannel.open(tempFile("signer-app.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock != null) {
                writePidFile();
                return Outcome.FIRST_INSTANCE;
            }
            log.info("Another instance is already running — asking it to show its window");
            if (activateExistingInstance(invitationToken)) {
                return Outcome.HANDED_OFF;
            }
            // Exiting silently here is what once made "the app doesn't
            // launch" with no explanation: the caller asks the user instead.
            log.warn("The running instance did not respond");
            return Outcome.OTHER_UNRESPONSIVE;
        } catch (IOException e) {
            // Never brick the app over the guard: start anyway, log why.
            log.warn("Single-instance lock unavailable: {}", e.toString());
            return Outcome.FIRST_INSTANCE;
        }
    }

    /**
     * Ends the unresponsive copy recorded in the PID file — only when that
     * PID is still a Java process (a reused PID of another program is never
     * touched) — then waits for the lock.
     *
     * @return true when this process now holds the lock
     */
    boolean replaceUnresponsiveInstance() {
        try {
            long pid = Long.parseLong(Files.readString(tempFile("signer-app.pid")).trim());
            ProcessHandle.of(pid)
                    .filter(p -> p.pid() != ProcessHandle.current().pid())
                    .filter(p -> p.info().command().map(SingleInstanceGuard::isJava).orElse(false))
                    .ifPresent(p -> {
                        log.info("Ending the unresponsive SignFlow instance (PID {})", p.pid());
                        p.destroyForcibly();
                    });
        } catch (Exception e) {
            log.info("No previous SignFlow PID to end: {}", e.toString());
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                lock = lockChannel.tryLock();
                if (lock != null) {
                    writePidFile();
                    return true;
                }
            } catch (IOException | OverlappingFileLockException e) {
                return false;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Starts answering second launches. Each request runs {@code handler} on
     * the JavaFX thread and replies 200 only once a window was really raised
     * (503 otherwise), so a stuck copy is reported instead of hiding launches.
     */
    void startActivationListener(ActivationHandler handler) {
        try {
            activationServer = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", ACTIVATION_PORT), 0);
            activationServer.createContext("/", exchange -> {
                String forwarded = InvitationLinks.parseActivationToken(
                        exchange.getRequestURI().getRawQuery());
                CountDownLatch done = new CountDownLatch(1);
                AtomicBoolean raised = new AtomicBoolean();
                Platform.runLater(() -> {
                    try {
                        raised.set(handler.activate(forwarded));
                    } finally {
                        done.countDown();
                    }
                });
                boolean answered;
                try {
                    answered = done.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    answered = false;
                }
                boolean ok = answered && raised.get();
                byte[] body = (ok ? "activated" : "unresponsive").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(ok ? 200 : 503, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            activationServer.start();
        } catch (Exception e) {
            // Non-fatal: the lock still prevents duplicates.
            log.warn("Activation listener unavailable: {}", e.toString());
        }
    }

    /** Stops the activation listener (the OS releases the lock on exit). */
    void close() {
        if (activationServer != null) {
            activationServer.stop(0);
        }
    }

    /** Second launch: ask the running instance to show itself. */
    private static boolean activateExistingInstance(String invitationToken) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            String query = invitationToken != null ? "?token=" + invitationToken : "";
            return client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + ACTIVATION_PORT + "/" + query))
                            .timeout(Duration.ofSeconds(6))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writePidFile() {
        try {
            Files.writeString(tempFile("signer-app.pid"),
                    String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException e) {
            log.debug("PID file not written: {}", e.toString());
        }
    }

    private static boolean isJava(String command) {
        String c = command.toLowerCase(Locale.ROOT);
        return c.endsWith("java.exe") || c.endsWith("/java") || c.endsWith("javaw.exe");
    }

    private static Path tempFile(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), name);
    }
}
