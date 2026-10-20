package com.example.signer.ui;

import com.example.signer.ui.InvitationLinks.LaunchArgument;
import com.example.signer.ui.SingleInstanceGuard.Outcome;
import com.example.signer.ui.SingleInstanceGuard.Reply;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authenticated single-instance hand-off (Phase B, remediation H1 + M1).
 *
 * <p>Platform notes: the permission checks here exercise the <b>POSIX</b>
 * implementation (they are skipped, not passed, elsewhere). The Windows
 * ACL logic is tested as pure logic in {@link WindowsAclPolicyTests}; the
 * real Windows ACL calls only run on Windows.
 */
class SingleInstanceGuardTests {

    private static final String CODE = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms";
    private static final Duration SHORT = Duration.ofMillis(900);

    @TempDir
    Path temp;

    private final List<SingleInstanceGuard> guards = new CopyOnWriteArrayList<>();
    private final List<Process> children = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() {
        guards.forEach(SingleInstanceGuard::close);
        for (Process p : children) {
            p.destroy();
        }
    }

    private SingleInstanceGuard guard(Path dir) {
        return guard(dir, SHORT);
    }

    private SingleInstanceGuard guard(Path dir, Duration wait) {
        SingleInstanceGuard g = new SingleInstanceGuard(dir, wait);
        guards.add(g);
        return g;
    }

    private static boolean posix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static boolean root(Path p) throws IOException {
        Path probe = Files.createTempFile(p, "uid", ".probe");
        try {
            return Integer.valueOf(0).equals(Files.getAttribute(probe, "unix:uid"));
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /** A running first instance collecting what it receives. */
    private SingleInstanceGuard running(Path dir, List<LaunchArgument> received) {
        SingleInstanceGuard first = guard(dir);
        assertThat(first.acquire(null)).isEqualTo(Outcome.FIRST_INSTANCE);
        first.startActivationListener(a -> {
            received.add(a);
            return Reply.ACCEPTED;
        });
        assertThat(first.channelReady()).isTrue();
        return first;
    }

    private static LaunchArgument launch() {
        return new LaunchArgument(LaunchArgument.Kind.LAUNCH, CODE);
    }

    /* --------------------------- basic hand-off ------------------------- */

    @Test
    void aSecondLaunchHandsItsLinkToTheRunningCopy() {
        Path dir = temp.resolve("run");
        List<LaunchArgument> received = new CopyOnWriteArrayList<>();
        running(dir, received);
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.HANDED_OFF);
        assertThat(guard(dir).acquire(null)).isEqualTo(Outcome.HANDED_OFF);
        assertThat(received).hasSize(2);
        assertThat(received.get(0).kind()).isEqualTo(LaunchArgument.Kind.LAUNCH);
        assertThat(received.get(0).value()).isEqualTo(CODE);
        assertThat(received.get(1)).isNull();
    }

    @Test
    void noNetworkPortIsOpenedAndTheFilesAreCreatedPrivate() throws Exception {
        Path dir = temp.resolve("run");
        running(dir, new CopyOnWriteArrayList<>());
        assertThat(Files.readAllBytes(dir.resolve("ipc.key"))).hasSize(32);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("no temporary key file left behind")
                    .noneMatch(n -> n.endsWith(".tmp"));
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 61234), 300);
            throw new AssertionError("something still listens on 61234");
        } catch (IOException expected) {
            // nothing listens there
        }
        Assumptions.assumeTrue(posix(dir), "POSIX permission check (Windows: see WindowsAclPolicyTests)");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("ipc.key")))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("ipc.sock")))).isEqualTo("rw-------");
    }

    @Test
    void aSquatterWithoutTheKeyReceivesNothing() throws Exception {
        Path dir = temp.resolve("run");
        SingleInstanceGuard.securePrivateDirectory(dir);
        byte[] realKey = new byte[32];
        new java.security.SecureRandom().nextBytes(realKey);
        SingleInstanceGuard.writeKeyFile(dir, realKey);      // a key the squatter does not hold
        AtomicInteger bytesAfterProof = new AtomicInteger(-1);
        try (ServerSocketChannel squatter = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
             FileChannel ch = FileChannel.open(dir.resolve("instance.lock"),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = ch.lock()) {
            squatter.bind(UnixDomainSocketAddress.of(dir.resolve("ipc.sock")));
            Thread t = new Thread(() -> {
                try (SocketChannel c = squatter.accept()) {
                    DataInputStream in = new DataInputStream(Channels.newInputStream(c));
                    DataOutputStream out = new DataOutputStream(Channels.newOutputStream(c));
                    in.readNBytes(6 + 32);
                    out.write(new byte[32]);      // nonce
                    out.write(new byte[32]);      // forged proof
                    out.flush();
                    bytesAfterProof.set(in.readAllBytes().length);
                } catch (Exception e) {
                    bytesAfterProof.compareAndSet(-1, 0);
                }
            });
            t.start();
            assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.ALREADY_RUNNING_UNVERIFIED);
            t.join(5000);
        }
        assertThat(bytesAfterProof.get()).isZero();
    }

    @Test
    void aForgedMessageIsRejectedByTheRunningCopy() throws Exception {
        Path dir = temp.resolve("run");
        List<LaunchArgument> received = new CopyOnWriteArrayList<>();
        running(dir, received);
        try (SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            c.connect(UnixDomainSocketAddress.of(dir.resolve("ipc.sock")));
            DataInputStream in = new DataInputStream(Channels.newInputStream(c));
            DataOutputStream out = new DataOutputStream(Channels.newOutputStream(c));
            out.write("SFIPC1".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[32]);
            out.flush();
            in.readNBytes(64);                     // server nonce + proof
            byte[] value = CODE.getBytes(StandardCharsets.US_ASCII);
            out.writeByte(2);
            out.writeShort(value.length);
            out.write(value);
            out.write(new byte[32]);               // wrong MAC
            out.flush();
            assertThat(in.read()).isZero();
        }
        assertThat(received).isEmpty();
    }

    /* ------------------- fail closed: directory and key ----------------- */

    @Test
    void aSymbolicLinkInsteadOfTheDirectoryIsRefused() throws Exception {
        Path real = Files.createDirectory(temp.resolve("elsewhere"));
        Path link = temp.resolve("run-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links cannot be created here");
        }
        assertThat(guard(link).acquire(launch())).isEqualTo(Outcome.STANDALONE);
        try (var files = Files.list(real)) {
            assertThat(files).as("nothing written through the link").isEmpty();
        }
    }

    @Test
    void aDirectoryOwnedByAnotherAccountIsNeverUsed() throws Exception {
        Path dir = temp.resolve("run");
        Assumptions.assumeTrue(posix(temp) && root(temp), "needs POSIX and root to change owners");
        Files.createDirectory(dir);
        Files.setOwner(dir, dir.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("nobody"));
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.STANDALONE);
        try (var files = Files.list(dir)) {
            assertThat(files).as("no lock, key or socket created there").isEmpty();
        }
    }

    @Test
    void looseDirectoryPermissionsAreTightenedThenVerified() throws Exception {
        Path dir = temp.resolve("run");
        Assumptions.assumeTrue(posix(temp), "POSIX permissions");
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThat(guard(dir).acquire(null)).isEqualTo(Outcome.FIRST_INSTANCE);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                .isEqualTo("rwx------");
    }

    @Test
    void aKeyFileThatFailsVerificationIsNeverTrusted() throws Exception {
        Assumptions.assumeTrue(posix(temp), "POSIX permissions");
        List<Consumer<Path>> tamperings = new java.util.ArrayList<>(List.of(
                key -> setPerms(key, "rw-r--r--"),                  // readable by others
                key -> setPerms(key, "rw-rw----"),                  // group access
                key -> rewrite(key, new byte[33]),                  // wrong size
                key -> replaceWithLink(key)));                      // a link to another file
        if (root(temp)) {
            tamperings.add(key -> chownNobody(key));                // another account's file
        }
        int i = 0;
        for (Consumer<Path> tamper : tamperings) {
            Path dir = temp.resolve("run" + (i++));
            List<LaunchArgument> received = new CopyOnWriteArrayList<>();
            running(dir, received);
            tamper.accept(dir.resolve("ipc.key"));
            assertThat(guard(dir).acquire(launch())).as("tampering " + i)
                    .isEqualTo(Outcome.ALREADY_RUNNING_UNVERIFIED);
            assertThat(received).as("tampering " + i).isEmpty();
        }
    }

    /* ---------------- start-up, recovery: never "dead", never killed ----- */

    @Test
    void aHeldLockWithoutSocketMeansStartingOrUnverifiedNotDead() throws Exception {
        Path dir = temp.resolve("run");
        SingleInstanceGuard.securePrivateDirectory(dir);
        try (FileChannel ch = FileChannel.open(dir.resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = ch.lock()) {
            long start = System.nanoTime();
            assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.ALREADY_RUNNING_UNVERIFIED);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("waited for a copy that may be starting").isGreaterThanOrEqualTo(SHORT);
        }
    }

    @Test
    void aFailedBindLeavesNoListenerAndSecondLaunchesAreToldSo() throws Exception {
        Path dir = temp.resolve("run");
        SingleInstanceGuard first = guard(dir);
        assertThat(first.acquire(null)).isEqualTo(Outcome.FIRST_INSTANCE);
        // Something that cannot be replaced sits where the socket goes.
        Files.createDirectories(dir.resolve("ipc.sock").resolve("blocker"));
        first.startActivationListener(a -> Reply.ACCEPTED);
        assertThat(first.channelReady()).isFalse();
        assertThat(dir.resolve("ipc.key")).doesNotExist();
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.ALREADY_RUNNING_UNVERIFIED);
    }

    @Test
    void staleFilesOfACrashedRunAreReplaced() throws Exception {
        Path dir = temp.resolve("run");
        SingleInstanceGuard.securePrivateDirectory(dir);
        byte[] oldKey = new byte[32];
        SingleInstanceGuard.writeKeyFile(dir, oldKey);
        try (ServerSocketChannel crashed = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            crashed.bind(UnixDomainSocketAddress.of(dir.resolve("ipc.sock")));
        }   // the socket file stays behind, nobody listens, nobody holds the lock
        assertThat(dir.resolve("ipc.sock")).exists();
        List<LaunchArgument> received = new CopyOnWriteArrayList<>();
        running(dir, received);
        assertThat(Files.readAllBytes(dir.resolve("ipc.key"))).isNotEqualTo(oldKey);
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.HANDED_OFF);
        assertThat(received).hasSize(1);
    }

    @Test
    void aClosingCopyMakesTheSecondLaunchWaitAndTakeOver() throws Exception {
        Path dir = temp.resolve("run");
        SingleInstanceGuard first = guard(dir);
        assertThat(first.acquire(null)).isEqualTo(Outcome.FIRST_INSTANCE);
        ActivationQueue queue = new ActivationQueue(System::currentTimeMillis);
        first.startActivationListener(queue);
        queue.close();                              // exiting: answers "closing"
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            first.close();                          // releases the lock
        });
        closer.start();
        assertThat(guard(dir, Duration.ofSeconds(5)).acquire(launch()))
                .isEqualTo(Outcome.FIRST_INSTANCE);
        closer.join();
    }

    @Test
    void aRepeatedLinkIsOpenedOnce() {
        Path dir = temp.resolve("run");
        SingleInstanceGuard first = guard(dir);
        assertThat(first.acquire(null)).isEqualTo(Outcome.FIRST_INSTANCE);
        ActivationQueue queue = new ActivationQueue(System::currentTimeMillis);
        first.startActivationListener(queue);
        List<LaunchArgument> opened = new CopyOnWriteArrayList<>();
        queue.ready(opened::add);
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.HANDED_OFF);
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.HANDED_OFF);
        assertThat(opened).hasSize(1);
    }

    @Test
    void linksArrivingBeforeTheWindowExistsAreQueued() {
        Path dir = temp.resolve("run");
        SingleInstanceGuard first = guard(dir);
        assertThat(first.acquire(null)).isEqualTo(Outcome.FIRST_INSTANCE);
        ActivationQueue queue = new ActivationQueue(System::currentTimeMillis);
        first.startActivationListener(queue);       // window not ready yet
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.HANDED_OFF);
        assertThat(queue.pendingCount()).isEqualTo(1);
        assertThat(queue.ready(a -> { })).extracting(LaunchArgument::value).containsExactly(CODE);
    }

    /* ------------------------- two real processes ----------------------- */

    private static final class Child {
        final Process process;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        Child(Process process) {
            this.process = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("CHILD ")) {
                            lines.add(line.substring(6));
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        String await(String prefix) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                String line = lines.poll(1, TimeUnit.SECONDS);
                if (line != null && line.startsWith(prefix)) {
                    return line;
                }
            }
            throw new AssertionError("child never printed " + prefix);
        }
    }

    private Child child(Path dir, String mode) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SingleInstanceChild.class.getName(), dir.toString(), mode);
        pb.redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        children.add(p);
        return new Child(p);
    }

    @Test
    void twoRealProcessesHandOverALink() throws Exception {
        Path dir = temp.resolve("run");
        Child running = child(dir, "listen");
        assertThat(running.await("OUTCOME")).isEqualTo("OUTCOME FIRST_INSTANCE");
        assertThat(running.await("READY")).isEqualTo("READY true");
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.HANDED_OFF);
        assertThat(running.await("GOT")).isEqualTo("GOT LAUNCH " + CODE);
    }

    @Test
    void aSecondClickWhileTheOtherProcessIsStillStartingIsHandedOverNotFlaggedAsStuck()
            throws Exception {
        Path dir = temp.resolve("run");
        Child starting = child(dir, "late:1500");
        assertThat(starting.await("OUTCOME")).isEqualTo("OUTCOME FIRST_INSTANCE");
        // The lock is held but nothing listens yet.
        assertThat(guard(dir, Duration.ofSeconds(10)).acquire(launch()))
                .isEqualTo(Outcome.HANDED_OFF);
        assertThat(starting.await("GOT")).isEqualTo("GOT LAUNCH " + CODE);
    }

    @Test
    void aProcessWhoseChannelFailedIsReportedAndLeftRunning() throws Exception {
        Path dir = temp.resolve("run");
        Child silent = child(dir, "nolisten");
        assertThat(silent.await("OUTCOME")).isEqualTo("OUTCOME FIRST_INSTANCE");
        assertThat(silent.await("READY")).isEqualTo("READY false");
        assertThat(guard(dir).acquire(launch())).isEqualTo(Outcome.ALREADY_RUNNING_UNVERIFIED);
        assertThat(silent.process.isAlive()).as("never terminated").isTrue();
        assertThat(silent.lines).noneMatch(l -> l.startsWith("GOT"));
    }

    /* ------------------------------ helpers ----------------------------- */

    private static void setPerms(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void rewrite(Path p, byte[] bytes) {
        try {
            Files.write(p, bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void replaceWithLink(Path key) {
        try {
            Path other = key.resolveSibling("other-key");
            Files.write(other, Files.readAllBytes(key));
            Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rw-------"));
            Files.delete(key);
            Files.createSymbolicLink(key, other);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void chownNobody(Path p) {
        try {
            Files.setOwner(p, p.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName("nobody"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
