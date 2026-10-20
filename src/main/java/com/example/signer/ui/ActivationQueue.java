package com.example.signer.ui;

import com.example.signer.ui.InvitationLinks.LaunchArgument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Second-launch requests for the running SignFlow (Phase B remediation M1).
 *
 * <ul>
 *   <li>The hand-off listener starts as soon as the instance lock is held,
 *       long before the window exists; requests arriving meanwhile are
 *       <b>queued</b> (bounded) and dispatched once {@link #ready} is called.</li>
 *   <li>The same link arriving twice within two minutes (a browser that
 *       fires the protocol twice, a double click) is opened <b>once</b>.
 *       Only a hash of it is kept.</li>
 *   <li>After {@link #close} every request is answered "closing", so the
 *       second launch waits for this copy to exit instead of losing its link.</li>
 * </ul>
 * No JavaFX here: the dispatcher decides how a request reaches the UI.
 */
final class ActivationQueue implements SingleInstanceGuard.ActivationHandler {

    static final int MAX_PENDING = 8;
    static final long DUPLICATE_WINDOW_MS = 120_000;

    private final LongSupplier clock;
    private final Deque<Optional<LaunchArgument>> pending = new ArrayDeque<>();
    private final Map<String, Long> recent = new LinkedHashMap<>();
    private Consumer<LaunchArgument> dispatcher;
    private boolean closed;

    ActivationQueue(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public SingleInstanceGuard.Reply activate(LaunchArgument argument) {
        Consumer<LaunchArgument> target;
        synchronized (this) {
            if (closed) {
                return SingleInstanceGuard.Reply.CLOSING;
            }
            if (argument != null && !firstTimeSeen(argument)) {
                return SingleInstanceGuard.Reply.ACCEPTED;   // already being opened
            }
            if (dispatcher == null) {
                if (pending.size() >= MAX_PENDING) {
                    pending.removeFirst();
                }
                pending.addLast(Optional.ofNullable(argument));
                return SingleInstanceGuard.Reply.ACCEPTED;
            }
            target = dispatcher;
        }
        target.accept(argument);
        return SingleInstanceGuard.Reply.ACCEPTED;
    }

    /** The app's own start-up link: a forwarded copy of it is not opened again. */
    synchronized void markOpened(LaunchArgument argument) {
        if (argument != null) {
            firstTimeSeen(argument);
        }
    }

    /**
     * The window exists: from now on requests go to {@code dispatcher}; the
     * queued ones are returned in arrival order for the caller to dispatch.
     */
    List<LaunchArgument> ready(Consumer<LaunchArgument> dispatcher) {
        synchronized (this) {
            if (closed) {
                return List.of();
            }
            this.dispatcher = dispatcher;
            List<LaunchArgument> queued = new ArrayList<>();
            for (Optional<LaunchArgument> p : pending) {
                queued.add(p.orElse(null));
            }
            pending.clear();
            return queued;
        }
    }

    /** This copy is exiting: refuse new requests ("closing"), drop the queue. */
    synchronized void close() {
        closed = true;
        dispatcher = null;
        pending.clear();
        recent.clear();
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private boolean firstTimeSeen(LaunchArgument argument) {
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > DUPLICATE_WINDOW_MS) {
                it.remove();
            }
        }
        String key = fingerprint(argument);
        if (recent.containsKey(key)) {
            return false;
        }
        recent.put(key, now);
        return true;
    }

    private static String fingerprint(LaunchArgument argument) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(argument.kind().name().getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(argument.value().getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
