package com.example.signer.server.downloads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What there is to download, and the only way a request may name a file.
 *
 * <h2>Where the files are</h2>
 *
 * <p>{@code SIGNER_DOWNLOAD_DIR} — a directory <b>outside</b> the jar and
 * outside the repository. Publishing a release is copying two files into it
 * (see {@code docs/desktop-download.md}); nothing is built into the
 * application, so an installer can be replaced without rebuilding or
 * redeploying the server.
 *
 * <p>Unset is a supported state: then there is nothing to serve, and
 * {@code SIGNER_DOWNLOAD_URL} — the link the invitation page has used since
 * Phase 3 — is offered instead if it is configured. Both unset means the
 * website says the download is not available yet, which is the truth.
 *
 * <p><b>Set but unusable fails startup.</b> A deployment that names a
 * directory and gets it wrong wants to know at once, not the first time
 * somebody clicks Download.
 *
 * <h2>How a request may name a file</h2>
 *
 * <p>This is the whole of the path handling, and it is deliberately boring:
 * a name must match {@link #ALLOWED} <i>exactly</i>, which admits only
 * {@code SignFlow-<version>.msi}, {@code SignFlow-<version>-windows.zip} and
 * either of those with {@code .sha256} on the end. The character class has no
 * {@code /}, no {@code \} and no way to spell {@code ..} as a whole name, so
 * traversal is not defended against — it is unrepresentable. Percent-encoded
 * attempts are decoded by the container before they reach here and then fail
 * the same test.
 *
 * <p>Three further checks, because a matching name is not yet a safe file:
 * the resolved path's parent must be the directory itself (nothing in a
 * subdirectory), it must be a regular file, and its <i>real</i> path — with
 * symbolic links followed — must still be in that directory. A symlink
 * pointing at {@code C:\Windows\System32\config\SAM} is a file whose name
 * passes the pattern.
 *
 * <p>Every refusal is the same {@link Optional#empty()}: the caller answers
 * 404 and says nothing about which rule was broken, whether the file exists,
 * or where the directory is.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DownloadCatalog {

    private static final Logger log = LoggerFactory.getLogger(DownloadCatalog.class);

    /**
     * The only file names that can be asked for.
     *
     * <p>Anchored, so it matches a whole name and not a part of one. The
     * version part is {@code [A-Za-z0-9._-]+} rather than a strict semver
     * because a build may be {@code 0.1.0}, {@code 0.1.0-rc1} or
     * {@code 0.2.0-SNAPSHOT}, and the pattern's job is to keep separators and
     * dot-dot out, not to validate version syntax.
     */
    private static final Pattern ALLOWED = Pattern.compile(
            "SignFlow-[A-Za-z0-9._-]+\\.msi"
            + "|SignFlow-[A-Za-z0-9._-]+-windows\\.zip"
            + "|SignFlow-[A-Za-z0-9._-]+\\.msi\\.sha256"
            + "|SignFlow-[A-Za-z0-9._-]+-windows\\.zip\\.sha256");

    /** Pulls the version out of an artefact's name. */
    private static final Pattern VERSION_IN_NAME =
            Pattern.compile("SignFlow-(.+?)(?:-windows)?\\.(?:msi|zip)");

    /** A published artefact and what a page needs to say about it. */
    public record Artefact(String fileName, long sizeBytes, String version, String sha256) {
    }

    private final Path directory;

    private final String externalUrl;

    private final String appName;

    public DownloadCatalog(
            @Value("${signature.remote-server.download.dir:${SIGNER_DOWNLOAD_DIR:}}")
            String directory,
            @Value("${signature.remote-server.invitation.download-url:${SIGNER_DOWNLOAD_URL:}}")
            String externalUrl,
            @Value("${app.name:SignFlow}") String appName) {
        this.externalUrl = externalUrl == null || externalUrl.isBlank()
                ? null : externalUrl.trim();
        this.appName = appName;
        this.directory = validated(directory);
    }

    /**
     * Fails startup with a sentence somebody can act on, rather than serving
     * 404s that look like "no release published yet".
     */
    private static Path validated(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path;
        try {
            path = Paths.get(configured.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException(
                    "SIGNER_DOWNLOAD_DIR is not a usable path: " + e.getMessage(), e);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(
                    "SIGNER_DOWNLOAD_DIR is set but is not an existing directory."
                    + " Create it, or unset the variable to turn the download off.");
        }
        if (!Files.isReadable(path)) {
            throw new IllegalStateException(
                    "SIGNER_DOWNLOAD_DIR exists but this process cannot read it."
                    + " Check the folder's permissions.");
        }
        // The configured directory is a deployment fact, so it is logged once
        // at startup — and never again with a request's input anywhere near
        // it, which is what would turn a log into a path oracle.
        log.info("Downloads are served from the configured directory");
        return path;
    }

    /** The application's own name, for the page's wording. */
    public String appName() {
        return this.appName;
    }

    /** The external link, when one is configured and there are no local files. */
    public Optional<String> externalUrl() {
        return Optional.ofNullable(this.externalUrl);
    }

    /** The installer to offer: the newest {@code .msi} in the directory. */
    public Optional<Artefact> installer() {
        return newest("\\.msi$");
    }

    /** The portable build, for anyone who cannot run an installer. */
    public Optional<Artefact> portableZip() {
        return newest("-windows\\.zip$");
    }

    private Optional<Artefact> newest(String suffixPattern) {
        if (this.directory == null) {
            return Optional.empty();
        }
        Pattern suffix = Pattern.compile(suffixPattern);
        try (Stream<Path> files = Files.list(this.directory)) {
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .filter(p -> ALLOWED.matcher(p.getFileName().toString()).matches())
                    .filter(p -> suffix.matcher(p.getFileName().toString()).find())
                    .sorted(Comparator.comparing((Path p) -> lastModified(p)).reversed())
                    .toList();
            return candidates.isEmpty() ? Optional.empty() : Optional.of(describe(candidates.get(0)));
        } catch (IOException e) {
            // A directory that cannot be listed is "nothing published" to a
            // reader; the deployment hears about it here and nowhere else.
            log.warn("The download directory could not be listed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private Artefact describe(Path file) {
        String name = file.getFileName().toString();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0L;
        }
        var matcher = VERSION_IN_NAME.matcher(name);
        String version = matcher.matches() ? matcher.group(1) : "unknown";
        return new Artefact(name, size, version, sha256Beside(file).orElse(null));
    }

    /**
     * The checksum from the {@code .sha256} file published beside the
     * artefact, which is what {@code scripts/package.sh} writes. Nothing is
     * hashed here on demand: a reader waiting on a 200 MB read is a worse
     * answer than "no checksum published".
     */
    private Optional<String> sha256Beside(Path artefact) {
        Path sidecar = artefact.resolveSibling(artefact.getFileName() + ".sha256");
        try {
            if (!Files.isRegularFile(sidecar) || Files.size(sidecar) > 4096) {
                return Optional.empty();
            }
            String first = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
            // `sha256sum` writes "<hash>  <name>"; take the hash and check it
            // looks like one, so a mangled file cannot put arbitrary text on
            // a page.
            String hash = first.split("\\s+", 2)[0].toLowerCase();
            return hash.matches("[0-9a-f]{64}") ? Optional.of(hash) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The file a request named, or empty — see the class comment for every
     * rule applied here. Never throws: a caller must be able to answer 404
     * without a 500 being possible.
     */
    public Optional<Path> resolve(String fileName) {
        if (this.directory == null || fileName == null || fileName.isEmpty()) {
            return Optional.empty();
        }
        if (!ALLOWED.matcher(fileName).matches()) {
            return Optional.empty();
        }
        try {
            Path candidate = this.directory.resolve(fileName).normalize();
            // Directly in the folder: not a subdirectory, and not above it.
            if (!this.directory.equals(candidate.getParent())) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(candidate)) {
                return Optional.empty();
            }
            // Follow symbolic links and check again: a link whose own name
            // passes the pattern can still point anywhere on the disk.
            Path real = candidate.toRealPath();
            Path realDirectory = this.directory.toRealPath();
            if (!realDirectory.equals(real.getParent())) {
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers InvalidPathException too. Everything is
            // caught on purpose: the caller must be able to answer 404
            // without a 500 being reachable from a crafted name.
            return Optional.empty();
        }
    }
}
