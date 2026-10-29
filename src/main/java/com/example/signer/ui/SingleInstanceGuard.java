package com.example.signer.ui;

import com.example.signer.ui.InvitationLinks.LaunchArgument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Only one SignFlow runs at a time, and a second launch hands its link to
 * the running copy over an <b>authenticated local channel</b>.
 *
 * <h2>Why (security Phase B, finding F1; remediation H1 and M1)</h2>
 * The original version listened on {@code http://127.0.0.1:61234}. Binding to
 * loopback authenticates nobody: any local process could receive forwarded
 * secrets. This class replaces it.
 *
 * <h2>How</h2>
 * <ul>
 *   <li><b>No network port.</b> A Unix-domain socket (Windows 10 1803+, Java
 *       16+) in a per-user run directory ({@code %LOCALAPPDATA%\SignFlow\run},
 *       or {@code ~/.signflow/run}).</li>
 *   <li><b>The directory and key are verified, not assumed private.</b> The
 *       current account is the owner of a file this process has just created
 *       (its security identifier on Windows, its uid elsewhere) — never a
 *       name lookup. The directory must be a real directory (not a link or
 *       junction) owned by that account; its permissions are set to owner
 *       only (Windows: this account plus SYSTEM, inherited by what is created
 *       inside) and then <b>read back and checked</b>. The key file is created
 *       with those permissions from the start, written, verified and moved into
 *       place atomically. Before a second launch reads {@code ipc.key} it checks
 *       the directory and the key file the same way.</li>
 *   <li><b>Fail closed.</b> If any of that cannot be established, nothing is
 *       forwarded and no listener is started: the app runs on its own
 *       ({@link Outcome#STANDALONE}) or tells the user SignFlow is already
 *       running ({@link Outcome#ALREADY_RUNNING_UNVERIFIED}).</li>
 *   <li><b>Mutual proof of a per-run secret.</b> The second copy asks the
 *       running copy to prove it knows the key (HMAC over a fresh nonce) and
 *       sends <b>nothing</b> until the proof checks out; its own message is then
 *       HMAC-signed over the running copy's fresh nonce (no forgery, no
 *       replay).</li>
 *   <li><b>Never kills anything.</b> A copy that is starting, busy, or whose
 *       channel failed is reported, not terminated: it may be in the middle of
 *       signing.</li>
 * </ul>
 * No UI here: the caller supplies what "activate" means
 * ({@link ActivationHandler}).
 */
final class SingleInstanceGuard {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    private static final byte[] MAGIC = "SFIPC1".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 32;
    private static final int MAC_BYTES = 32;
    private static final int KEY_BYTES = 32;
    private static final int MAX_VALUE_BYTES = 512;
    private static final long IO_TIMEOUT_MS = 6_000;
    private static final long RETRY_PAUSE_MS = 150;
    /** How long a second launch waits for a starting or closing copy. */
    static final Duration DEFAULT_PEER_WAIT = Duration.ofSeconds(8);

    private static final byte KIND_NONE = 0;
    private static final byte KIND_INVITATION = 1;
    private static final byte KIND_LAUNCH = 2;

    private static final int STATUS_REFUSED = 0;
    private static final int STATUS_ACCEPTED = 1;
    private static final int STATUS_CLOSING = 2;

    static final String LOCK_FILE = "instance.lock";
    static final String KEY_FILE = "ipc.key";
    static final String SOCKET_FILE = "ipc.sock";

    /** What {@link #acquire} found. */
    enum Outcome {
        /** This process is the running SignFlow (lock held, directory verified). */
        FIRST_INSTANCE,
        /**
         * The run directory could not be verified as private (or the platform
         * lacks what the guard needs): run without single-instance and without
         * any hand-off listener. Nothing is forwarded anywhere.
         */
        STANDALONE,
        /** Another copy is running and accepted the hand-off: exit quietly. */
        HANDED_OFF,
        /**
         * Another copy runs, but its channel is not available or could not be
         * verified (still starting after the wait, listener failed, or the files
         * failed the checks). Nothing was forwarded.
         */
        ALREADY_RUNNING_UNVERIFIED,
        /** Another copy proved itself but did not take the link in time. Nothing else happens. */
        OTHER_UNRESPONSIVE
    }

    /** The running copy's answer to a verified hand-off. */
    enum Reply {
        ACCEPTED, REFUSED, CLOSING
    }

    /** Runs on the channel's thread when a second launch hands something over. */
    @FunctionalInterface
    interface ActivationHandler {
        /**
         * @param argument the forwarded link, or {@code null} for "just show the window"
         */
        Reply activate(LaunchArgument argument);
    }

    /** One hand-off attempt. */
    private enum Attempt {
        HANDED_OFF, NOT_READY, UNVERIFIED, UNRESPONSIVE
    }

    private final Path runDir;
    private final Duration peerWait;
    private final SecureRandom random = new SecureRandom();
    private FileChannel lockChannel;
    private FileLock lock;
    private ServerSocketChannel server;
    private Thread acceptThread;
    private byte[] key;
    private volatile boolean channelReady;
    private volatile boolean closing;

    SingleInstanceGuard() {
        this(defaultRunDirectory(), DEFAULT_PEER_WAIT);
    }

    /** For tests: an explicit run directory and wait. */
    SingleInstanceGuard(Path runDir, Duration peerWait) {
        this.runDir = runDir;
        this.peerWait = peerWait;
    }

    /** For tests: an explicit run directory. */
    SingleInstanceGuard(Path runDir) {
        this(runDir, DEFAULT_PEER_WAIT);
    }

    /** {@code %LOCALAPPDATA%\SignFlow\run}, or {@code ~/.signflow/run}. */
    static Path defaultRunDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (isWindows() && localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "SignFlow", "run");
        }
        return Path.of(System.getProperty("user.home"), ".signflow", "run");
    }

    /* ------------------------------ acquire ----------------------------- */

    /**
     * Tries to become the running instance; if another copy runs, hands it
     * {@code argument} (or just asks it to show its window). Waits a little
     * for a copy that is still starting or is closing.
     */
    Outcome acquire(LaunchArgument argument) {
        Outcome outcome = tryAcquire(argument);
        if (outcome != Outcome.FIRST_INSTANCE) {
            closeLockChannel();
        }
        return outcome;
    }

    private Outcome tryAcquire(LaunchArgument argument) {
        try {
            securePrivateDirectory(runDir);
            lockChannel = FileChannel.open(runDir.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            secureLockFile(runDir.resolve(LOCK_FILE));
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            log.warn("Single-instance guard unavailable ({}); running on its own, without"
                    + " hand-off", e.getClass().getSimpleName());
            closeLockChannel();
            return Outcome.STANDALONE;
        }
        long deadline = System.nanoTime() + peerWait.toNanos();
        boolean announced = false;
        while (true) {
            if (tryLock()) {
                return Outcome.FIRST_INSTANCE;
            }
            if (!announced) {
                log.info("Another instance is already running — handing over to it");
                announced = true;
            }
            Attempt attempt = handOffOnce(argument);
            switch (attempt) {
                case HANDED_OFF:
                    return Outcome.HANDED_OFF;
                case UNVERIFIED:
                    return Outcome.ALREADY_RUNNING_UNVERIFIED;
                case UNRESPONSIVE:
                    return Outcome.OTHER_UNRESPONSIVE;
                default:
                    break; // NOT_READY: starting or closing — wait and retry
            }
            if (System.nanoTime() > deadline) {
                log.info("The running SignFlow did not open its hand-off channel in time;"
                        + " nothing forwarded");
                return Outcome.ALREADY_RUNNING_UNVERIFIED;
            }
            if (!pause()) {
                return Outcome.ALREADY_RUNNING_UNVERIFIED;
            }
        }
    }

    private boolean tryLock() {
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;   // held by this JVM already (tests): another "instance"
        } catch (IOException e) {
            lock = null;
        }
        return lock != null;
    }

    private static boolean pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /* ---------------------------- second launch ------------------------- */

    /** One authenticated hand-off attempt (second launch). */
    private Attempt handOffOnce(LaunchArgument argument) {
        Path socket = runDir.resolve(SOCKET_FILE);
        try {
            BasicFileAttributes attrs = Files.readAttributes(socket, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isDirectory()) {
                return Attempt.UNVERIFIED;
            }
        } catch (NoSuchFileException e) {
            return Attempt.NOT_READY;           // still starting, or its listener failed
        } catch (IOException | SecurityException e) {
            return Attempt.UNVERIFIED;
        }
        byte[] secret;
        try {
            secret = readVerifiedKey(runDir);
        } catch (NoSuchFileException e) {
            return Attempt.NOT_READY;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            log.warn("The hand-off files could not be verified ({}); nothing forwarded",
                    e.getMessage());
            return Attempt.UNVERIFIED;
        }
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            try {
                channel.connect(UnixDomainSocketAddress.of(socket));
            } catch (ConnectException e) {
                return Attempt.NOT_READY;       // nobody listening (yet)
            }
            ScheduledExecutorService watchdog = timeout(channel);
            try {
                DataInputStream in = new DataInputStream(Channels.newInputStream(channel));
                DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
                byte[] clientNonce = nonce();
                out.write(MAGIC);
                out.write(clientNonce);
                out.flush();
                byte[] serverNonce = in.readNBytes(NONCE_BYTES);
                byte[] serverProof = in.readNBytes(MAC_BYTES);
                if (serverNonce.length != NONCE_BYTES || serverProof.length != MAC_BYTES) {
                    return Attempt.UNRESPONSIVE;
                }
                if (!MessageDigest.isEqual(serverProof,
                        mac(secret, "server", clientNonce, serverNonce))) {
                    // A copy that just started may have replaced the key after
                    // we read it: retry once it settles; otherwise refuse.
                    if (keyChangedSince(secret)) {
                        return Attempt.NOT_READY;
                    }
                    log.warn("The running SignFlow could not prove its identity; nothing forwarded");
                    return Attempt.UNVERIFIED;
                }
                byte kind = argument == null ? KIND_NONE
                        : argument.kind() == LaunchArgument.Kind.LAUNCH ? KIND_LAUNCH
                        : KIND_INVITATION;
                byte[] value = argument == null ? new byte[0]
                        : argument.value().getBytes(StandardCharsets.US_ASCII);
                out.writeByte(kind);
                out.writeShort(value.length);
                out.write(value);
                out.write(mac(secret, "client", serverNonce, new byte[] {kind}, value));
                out.flush();
                int status = in.read();
                return switch (status) {
                    case STATUS_ACCEPTED -> Attempt.HANDED_OFF;
                    case STATUS_CLOSING -> Attempt.NOT_READY;   // wait: it is exiting
                    default -> Attempt.UNRESPONSIVE;
                };
            } finally {
                watchdog.shutdownNow();
            }
        } catch (UnsupportedOperationException e) {
            log.warn("Local sockets are not supported here; nothing forwarded");
            return Attempt.UNVERIFIED;
        } catch (IOException e) {
            return Attempt.UNRESPONSIVE;        // connected, then no complete answer
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    private boolean keyChangedSince(byte[] used) {
        try {
            byte[] now = readVerifiedKey(runDir);
            boolean changed = !MessageDigest.isEqual(now, used);
            java.util.Arrays.fill(now, (byte) 0);
            return changed;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /* ---------------------------- first instance ------------------------ */

    /**
     * Starts answering second launches (first instance only). Called as soon
     * as the lock is held; {@code handler} queues requests until the window
     * exists. A fresh key is written for this run and a stale socket file of a
     * crashed run is replaced. On any failure no listener runs.
     */
    void startActivationListener(ActivationHandler handler) {
        if (lock == null) {
            return;
        }
        try {
            securePrivateDirectory(runDir);
            key = new byte[KEY_BYTES];
            random.nextBytes(key);
            writeKeyFile(runDir, key);
            Path socket = runDir.resolve(SOCKET_FILE);
            Files.deleteIfExists(socket);       // never follows a link
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            if (posix(runDir)) {
                // The directory is the boundary; this is defence in depth.
                Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            }
            channelReady = true;
            acceptThread = new Thread(() -> acceptLoop(handler), "signflow-ipc");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Non-fatal: the lock still prevents duplicates; a second launch
            // will say "already running" instead of forwarding.
            log.warn("Hand-off channel unavailable: {}", e.getClass().getSimpleName());
            channelReady = false;
            closeServer();
            deleteQuietly(runDir.resolve(KEY_FILE));
        }
    }

    /** Whether second launches can hand over to this instance. */
    boolean channelReady() {
        return channelReady;
    }

    private void acceptLoop(ActivationHandler handler) {
        while (server != null && server.isOpen()) {
            try (SocketChannel client = server.accept()) {
                ScheduledExecutorService watchdog = timeout(client);
                try {
                    serve(client, handler);
                } finally {
                    watchdog.shutdownNow();
                }
            } catch (IOException e) {
                if (server == null || !server.isOpen()) {
                    return;
                }
                log.debug("Hand-off connection failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void serve(SocketChannel client, ActivationHandler handler) throws IOException {
        DataInputStream in = new DataInputStream(Channels.newInputStream(client));
        DataOutputStream out = new DataOutputStream(Channels.newOutputStream(client));
        byte[] magic = in.readNBytes(MAGIC.length);
        byte[] clientNonce = in.readNBytes(NONCE_BYTES);
        if (!MessageDigest.isEqual(magic, MAGIC) || clientNonce.length != NONCE_BYTES) {
            return;
        }
        byte[] serverNonce = nonce();
        out.write(serverNonce);
        out.write(mac(key, "server", clientNonce, serverNonce));
        out.flush();
        int kind = in.read();
        int length = in.readUnsignedShort();
        if (length > MAX_VALUE_BYTES) {
            return;
        }
        byte[] value = in.readNBytes(length);
        byte[] clientMac = in.readNBytes(MAC_BYTES);
        if (value.length != length || !MessageDigest.isEqual(clientMac,
                mac(key, "client", serverNonce, new byte[] {(byte) kind}, value))) {
            log.warn("Rejected an unauthenticated hand-off attempt");
            out.write(STATUS_REFUSED);
            out.flush();
            return;
        }
        LaunchArgument argument;
        try {
            argument = switch (kind) {
                case KIND_NONE -> null;
                case KIND_INVITATION -> new LaunchArgument(LaunchArgument.Kind.INVITATION,
                        new String(value, StandardCharsets.US_ASCII));
                case KIND_LAUNCH -> new LaunchArgument(LaunchArgument.Kind.LAUNCH,
                        new String(value, StandardCharsets.US_ASCII));
                default -> throw new IllegalArgumentException("unknown kind");
            };
        } catch (IllegalArgumentException e) {
            out.write(STATUS_REFUSED);
            out.flush();
            return;
        }
        Reply reply;
        if (closing) {
            reply = Reply.CLOSING;
        } else {
            try {
                reply = handler.activate(argument);
            } catch (RuntimeException e) {
                log.warn("Activation failed: {}", e.getClass().getSimpleName());
                reply = Reply.REFUSED;
            }
        }
        out.write(switch (reply) {
            case ACCEPTED -> STATUS_ACCEPTED;
            case CLOSING -> STATUS_CLOSING;
            default -> STATUS_REFUSED;
        });
        out.flush();
    }

    /**
     * Stops the channel, removes its files and releases the lock. Second
     * launches arriving meanwhile are told "closing" and wait for the lock.
     */
    void close() {
        closing = true;
        closeServer();
        if (lock != null) {
            deleteQuietly(runDir.resolve(SOCKET_FILE));
            deleteQuietly(runDir.resolve(KEY_FILE));
            try {
                lock.release();
            } catch (IOException e) {
                log.debug("Lock release: {}", e.getClass().getSimpleName());
            }
            lock = null;
        }
        closeLockChannel();
        if (key != null) {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private void closeServer() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException e) {
            log.debug("Hand-off channel cleanup: {}", e.getClass().getSimpleName());
        }
        server = null;
    }

    private void closeLockChannel() {
        try {
            if (lockChannel != null && lock == null) {
                lockChannel.close();
                lockChannel = null;
            }
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Cleanup of {}: {}", file.getFileName(), e.getClass().getSimpleName());
        }
    }

    /* ---------------------- private directory & key file ---------------- */

    /**
     * Creates {@code dir} if needed and makes sure only this account can use
     * it — then <b>reads the result back</b>. Throws when the directory is a
     * link or junction, belongs to another account, or its permissions cannot
     * be set to and verified as owner-only.
     */
    static void securePrivateDirectory(Path dir) throws IOException {
        UserPrincipal me = currentAccount();
        boolean posix = posix(dir);
        if (!posix && !acl(dir)) {
            throw new IOException("no permission model to protect the run directory");
        }
        Path parent = dir.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            if (posix) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(dir, aclAttribute(windowsEntries(me, true)));
            }
        } catch (FileAlreadyExistsException ignored) {
            // existing directory: verified below like a new one
        }
        BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther() || !attrs.isDirectory()) {
            throw new IOException("run directory is not a plain directory (link or junction)");
        }
        if (!me.equals(Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("run directory is owned by another account");
        }
        if (posix) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } else {
            view(dir).setAcl(windowsEntries(me, true));
        }
        verifyOwnerOnly(dir, me, true, "run directory");
    }

    /**
     * Writes the per-run key: created owner-only from the outset under a
     * temporary name, verified, then moved into place atomically.
     */
    static void writeKeyFile(Path dir, byte[] keyBytes) throws IOException {
        UserPrincipal me = currentAccount();
        Path tmp = dir.resolve(KEY_FILE + "." + HexFormat.of().formatHex(randomBytes(8)) + ".tmp");
        FileAttribute<?> ownerOnly = posix(dir)
                ? PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                : aclAttribute(windowsEntries(me, false));
        try {
            try (SeekableByteChannel ch = Files.newByteChannel(tmp,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS), ownerOnly)) {
                ByteBuffer buffer = ByteBuffer.wrap(keyBytes);
                while (buffer.hasRemaining()) {
                    ch.write(buffer);
                }
            }
            verifyOwnerOnly(tmp, me, false, "key file");
            try {
                Files.move(tmp, dir.resolve(KEY_FILE), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic key replacement not supported here", e);
            }
            verifyKeyFile(dir.resolve(KEY_FILE), me);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Second launch: the directory and the key file must pass the same
     * checks as when they were written, then exactly {@value #KEY_BYTES}
     * bytes are read without following links.
     */
    static byte[] readVerifiedKey(Path dir) throws IOException {
        UserPrincipal me = currentAccount();
        BasicFileAttributes dirAttrs = Files.readAttributes(dir, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dirAttrs.isSymbolicLink() || dirAttrs.isOther() || !dirAttrs.isDirectory()
                || !me.equals(Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("run directory failed verification");
        }
        verifyOwnerOnly(dir, me, true, "run directory");
        Path file = dir.resolve(KEY_FILE);
        verifyKeyFile(file, me);
        byte[] bytes = new byte[KEY_BYTES];
        try (SeekableByteChannel ch = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (ch.read(buffer) < 0) {
                    throw new IOException("key file too short");
                }
            }
            if (ch.read(ByteBuffer.allocate(1)) > 0) {
                throw new IOException("key file too long");
            }
        }
        return bytes;
    }

    private static void verifyKeyFile(Path file, UserPrincipal me) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()
                || attrs.size() != KEY_BYTES) {
            throw new IOException("key file is not a plain " + KEY_BYTES + "-byte file");
        }
        if (!me.equals(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("key file is owned by another account");
        }
        verifyOwnerOnly(file, me, false, "key file");
    }

    /**
     * Makes sure a pre-existing {@code instance.lock} cannot be used by another
     * account. The lock file outlives a run (it is never rewritten), so an
     * earlier owner-only ACL can have been widened since — by an administrator,
     * or by re-enabled inheritance. The permissions are therefore read back and,
     * because this account owns the file, re-applied once and verified again
     * before the lock is trusted.
     */
    private static void secureLockFile(Path lock) throws IOException {
        UserPrincipal me = currentAccount();
        if (!me.equals(Files.getOwner(lock, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("lock file is owned by another account");
        }
        try {
            verifyOwnerOnly(lock, me, false, "lock file");
        } catch (IOException widened) {
            applyOwnerOnly(lock, me);
            verifyOwnerOnly(lock, me, false, "lock file");
        }
    }

    /** Sets owner-only permissions on a file (POSIX 600, or this account and SYSTEM). */
    private static void applyOwnerOnly(Path path, UserPrincipal me) throws IOException {
        if (posix(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } else {
            view(path).setAcl(windowsEntries(me, false));
        }
    }

    /** Reads the permissions back: owner-only (POSIX) or only this account and SYSTEM (Windows). */
    private static void verifyOwnerOnly(Path path, UserPrincipal me, boolean directory,
            String what) throws IOException {
        if (posix(path)) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path,
                    LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> expected = PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------");
            if (!actual.equals(expected)) {
                throw new IOException(what + " is accessible to other accounts");
            }
            return;
        }
        if (!aclIsPrivate(view(path).getAcl(), me, windowsAllowed(me))) {
            throw new IOException(what + " grants access to other accounts");
        }
    }

    /**
     * Pure check of a Windows ACL (as read back, inherited entries included):
     * every ALLOW entry names an allowed principal, and the account itself can
     * read and write. DENY and audit entries do not grant anything.
     */
    static boolean aclIsPrivate(List<AclEntry> acl, UserPrincipal me, Set<UserPrincipal> allowed) {
        boolean meCanUse = false;
        for (AclEntry e : acl) {
            if (e.type() != AclEntryType.ALLOW) {
                continue;
            }
            if (!allowed.contains(e.principal())) {
                return false;
            }
            if (e.principal().equals(me)
                    && e.permissions().contains(AclEntryPermission.READ_DATA)
                    && e.permissions().contains(AclEntryPermission.WRITE_DATA)) {
                meCanUse = true;
            }
        }
        return meCanUse;
    }

    /**
     * The account this process runs as: the owner of a file it has just
     * created (CREATE_NEW, random name, in the per-user temporary directory)
     * — on Windows the token's owner SID, elsewhere its uid. No name lookup.
     */
    static UserPrincipal currentAccount() throws IOException {
        UserPrincipal cached = currentAccount;
        if (cached != null) {
            return cached;
        }
        Path probe = Files.createTempFile("signflow-owner-", ".probe");
        try {
            UserPrincipal owner = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            currentAccount = owner;
            return owner;
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static volatile UserPrincipal currentAccount;

    private static Set<UserPrincipal> windowsAllowed(UserPrincipal me) {
        Set<UserPrincipal> allowed = new LinkedHashSet<>();
        allowed.add(me);
        UserPrincipal system = windowsSystem();
        if (system != null) {
            allowed.add(system);
        }
        return allowed;
    }

    /** SYSTEM (S-1-5-18); its name is not localized. Null when it cannot be resolved. */
    private static UserPrincipal windowsSystem() {
        try {
            return Path.of(System.getProperty("java.io.tmpdir")).getFileSystem()
                    .getUserPrincipalLookupService().lookupPrincipalByName("NT AUTHORITY\\SYSTEM");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static List<AclEntry> windowsEntries(UserPrincipal me, boolean directory) {
        Set<AclEntryFlag> flags = directory
                ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                : EnumSet.noneOf(AclEntryFlag.class);
        List<AclEntry> entries = new ArrayList<>();
        for (UserPrincipal who : windowsAllowed(me)) {
            entries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(who)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(flags)
                    .build());
        }
        return entries;
    }

    private static FileAttribute<List<AclEntry>> aclAttribute(List<AclEntry> entries) {
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return entries;
            }
        };
    }

    private static AclFileAttributeView view(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path,
                AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("ACLs are not supported here");
        }
        return view;
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static boolean acl(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("acl");
    }

    /* ----------------------------- helpers ------------------------------ */

    private byte[] nonce() {
        byte[] n = new byte[NONCE_BYTES];
        random.nextBytes(n);
        return n;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] mac(byte[] secret, String label, byte[]... parts) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
            hmac.update(label.getBytes(StandardCharsets.US_ASCII));
            for (byte[] part : parts) {
                hmac.update(intBytes(part.length));
                hmac.update(part);
            }
            return hmac.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] intBytes(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** Closes {@code channel} after the I/O timeout, so a silent peer cannot hang us. */
    private static ScheduledExecutorService timeout(SocketChannel channel) {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "signflow-ipc-timeout");
            t.setDaemon(true);
            return t;
        });
        watchdog.schedule(() -> {
            try {
                channel.close();
            } catch (IOException ignored) {
                // closing is all we can do
            }
        }, IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return watchdog;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
