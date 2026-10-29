package com.example.signer.ui;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Test helper run as a <b>separate JVM</b> by {@link SingleInstanceGuardTests}:
 * a second, real SignFlow process holding the instance lock.
 *
 * <p>Arguments: {@code <runDir> <mode>}; mode {@code listen} (start the
 * listener at once), {@code late:<ms>} (hold the lock, start listening after
 * the delay — a copy that is still starting), {@code nolisten} (hold the lock,
 * never listen — a copy whose channel failed). Prints {@code CHILD ...} lines;
 * exits when its standard input is closed.
 */
public final class SingleInstanceChild {

    private SingleInstanceChild() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String mode = args[1];
        SingleInstanceGuard guard = new SingleInstanceGuard(dir, Duration.ofSeconds(2));
        SingleInstanceGuard.Outcome outcome = guard.acquire(null);
        say("OUTCOME " + outcome);
        if (outcome != SingleInstanceGuard.Outcome.FIRST_INSTANCE) {
            return;
        }
        if (mode.startsWith("late:")) {
            Thread.sleep(Long.parseLong(mode.substring(5)));
        }
        if (!mode.equals("nolisten")) {
            guard.startActivationListener(arg -> {
                say("GOT " + (arg == null ? "NONE" : arg.kind() + " " + arg.value()));
                return SingleInstanceGuard.Reply.ACCEPTED;
            });
        }
        say("READY " + guard.channelReady());
        while (System.in.read() >= 0) {
            // wait for the parent to close our input
        }
        guard.close();
    }

    private static synchronized void say(String line) {
        System.out.println("CHILD " + line);
        System.out.flush();
    }
}
