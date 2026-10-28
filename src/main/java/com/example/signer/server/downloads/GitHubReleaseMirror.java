package com.example.signer.server.downloads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes the latest GitHub release into the download folder on demand.
 *
 * <p>Before, publishing a release was an operator copying files into
 * {@code SIGNER_DOWNLOAD_DIR} by hand. Now: when a request asks for a file
 * the folder does not hold — and the name is on the allow-list and belongs
 * to the latest release — this component fetches the matching GitHub asset
 * once, verifies its bytes against the digest GitHub publishes for it
 * <i>before</i> they land on disk, and moves it into the folder. From then
 * on {@link DownloadCatalog} serves it like any other published file, and
 * the public download page starts describing the same release for free,
 * because both read the same catalog.
 *
 * <p>The deliberate boundaries:
 *
 * <ul>
 *   <li>A file the catalog already resolves is served from the folder; the
 *       mirror only fills gaps. An existing deployment's files win.</li>
 *   <li>Both gates pass first: the name must be allow-listed, and it must
 *       be part of the latest release's asset list. A name GitHub has never
 *       published here cannot summon a fetch.</li>
 *   <li>Bytes are streamed once, checksummed while reading, and the file
 *       only ever appears complete — as {@code .part-} scratch first (which
 *       the allow-list cannot serve), verified, then moved in.</li>
 *   <li>The checksum sidecar is written out of the binary's digest rather
 *       than downloaded, so a folder full at one moment is self-consistent.</li>
 *   <li>Concurrent requests for the same file are serialised per file: one
 *       fetch, everyone waits, nobody downloads the same 120 MB twice.</li>
 * </ul>
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GitHubReleaseMirror {

    private static final Logger log = LoggerFactory.getLogger(GitHubReleaseMirror.class);

    private final DownloadCatalog catalog;
    private final LatestReleaseService releases;
    private final GitHubReleaseClient client;

    /**
     * One lock per file name being mirrored, so a burst of downloaders who
     * want the same missing release produces exactly one upstream fetch.
     * The keys are allow-listed names only, so the map is finite in the ways
     * that matter.
     */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public GitHubReleaseMirror(DownloadCatalog catalog, LatestReleaseService releases,
            GitHubReleaseClient client) {
        this.catalog = catalog;
        this.releases = releases;
        this.client = client;
    }

    /** Whether mirroring CAN happen here: a download folder and a GitHub token. */
    public boolean possible() {
        return this.client.enabled() && this.catalog.directory().isPresent();
    }

    /**
     * The file, mirrored on first request when it was missing. Empty when
     * this deployment cannot mirror, when the name is not the latest
     * release's to publish, or when the fetch or its verification failed —
     * the caller answers 404, exactly as it would for any absence. Never
     * throws, so the download route can still be a ceiling of quiet 404s.
     */
    public Optional<Path> mirrorFile(String fileName) {
        if (!this.possible() || !DownloadCatalog.isAllowed(fileName)) {
            return Optional.empty();
        }
        // The sidecar's checksum is the BINARY's digest, which this method
        // would look up in the release snapshot and never find under the
        // sidecar's own name — handled in its own branch below.
        if (fileName.endsWith(".sha256")) {
            return checksumFile(fileName);
        }
        GitHubReleaseClient.Asset asset = this.releases.asset(fileName).orElse(null);
        if (asset == null || asset.sha256().isEmpty()) {
            return Optional.empty();
        }
        Object lock = this.locks.computeIfAbsent(fileName, key -> new Object());
        synchronized (lock) {
            // Somebody mirroring the same file just finished while we waited
            // for our turn: serve theirs rather than fetching anew.
            Optional<Path> existing = this.catalog.resolve(fileName);
            return existing.isPresent() ? existing : fetchInto(asset, fileName);
        }
    }

    /**
     * The checksum sidecar, written from the binary's digest: mirror the
     * binary first (a caller may only have ever asked for the checksum),
     * then, only if no sidecar is already there, write one in the standard
     * {@code <hash>  <name>} form.
     */
    private Optional<Path> checksumFile(String fileName) {
        Path dir = this.catalog.directory().orElse(null);
        if (dir == null) {
            return Optional.empty();
        }
        String artefact = fileName.substring(0, fileName.length() - ".sha256".length());
        GitHubReleaseClient.Asset binary = this.releases.asset(artefact).orElse(null);
        if (binary == null || binary.sha256().isEmpty()) {
            return Optional.empty();
        }
        // Recursion is one call to a name that cannot end in ".sha256", so
        // a sidecar of a sidecar is unrepresentable.
        if (mirrorFile(artefact).isEmpty()) {
            return Optional.empty();
        }
        try {
            writeSidecar(dir, artefact, binary.sha256());
            return this.catalog.resolve(fileName);
        } catch (IOException | RuntimeException e) {
            log.warn("The checksum sidecar could not be written: {}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** The {@code <hash>  <name>} record beside an artefact, if not already there. */
    private void writeSidecar(Path dir, String artefact, String sha256) throws IOException {
        Path sidecar = dir.resolve(artefact + ".sha256");
        if (!Files.isRegularFile(sidecar)) {
            Files.writeString(sidecar, sha256 + "  " + artefact + "\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * The one upstream fetch: scratch file, verified bytes, moved in only
     * when complete. Any failure leaves the folder as it was — the scratch
     * name is outside the allow-list, so nothing half-downloaded can ever
     * be served, and it is deleted here whatever happens.
     */
    private Optional<Path> fetchInto(GitHubReleaseClient.Asset asset, String fileName) {
        Path dir = this.catalog.directory().orElseThrow(
                () -> new IllegalStateException("checked by possible()"));
        Path scratch = dir.resolve(fileName + ".part-" + UUID.randomUUID());
        try {
            if (!this.client.downloadAsset(asset, scratch)) {
                log.warn("A release asset could not be fetched and verified from GitHub");
                Files.deleteIfExists(scratch);
                return Optional.empty();
            }
            // An operator may have copied the same release in while we
            // fetched. Theirs wins; ours is only ever a filler.
            Optional<Path> raced = this.catalog.resolve(fileName);
            if (raced.isPresent()) {
                Files.deleteIfExists(scratch);
                return raced;
            }
            Path target = dir.resolve(fileName);
            Files.move(scratch, target);
            // The sidecar belongs to the release as much as the file does:
            // written from the verified digest, never overwriting one an
            // operator already published.
            writeSidecar(dir, fileName, asset.sha256());
            return this.catalog.resolve(fileName);
        } catch (IOException | RuntimeException e) {
            log.warn("A release asset could not be mirrored into the download folder: {}",
                    e.getClass().getSimpleName());
            try {
                Files.deleteIfExists(scratch);
            } catch (IOException quiet) {
                log.warn("A half-downloaded release file could not be deleted");
            }
            return Optional.empty();
        }
    }
}
