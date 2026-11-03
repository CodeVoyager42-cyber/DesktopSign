package com.example.signer.server.web;

import com.example.signer.server.downloads.DownloadCatalog;
import com.example.signer.server.downloads.GitHubReleaseClient;
import com.example.signer.server.downloads.GitHubReleaseMirror;
import com.example.signer.server.downloads.LatestReleaseService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code GET /web/api/latest-release} — the newest release of the source
 * repository, as the signed-in website reads it.
 *
 * <p>The dashboard's download section calls this instead of the public
 * {@code /web/api/download-info}, for a difference of intent: that one
 * describes whatever this deployment's folder holds; this one describes the
 * release GitHub publishes, with this server as the delivery route. The
 * authentication needs no spelling here — an endpoint under
 * {@code /web/api/**} that no public matcher opens requires the session,
 * which is the rule this class inherits rather than repeats.
 *
 * <p>Two sources, honestly ordered. While GitHub is configured and answerable,
 * it is the truth about the version, the sizes and the checksums, and the
 * mirror ({@link GitHubReleaseMirror}) is why every released artefact is
 * servable from this origin even before it has ever been requested. When
 * GitHub is not configured (or a cold start met a failing source), the
 * download folder remains the whole truth, exactly as it always was — the
 * same shape, without a tag or a publish date.
 *
 * <p>{@code available} is again the one field a page branches on: a button
 * whose URL is null is never rendered, and no other field is asked about it.
 */
@RestController
@RequestMapping("/web/api")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebLatestReleaseController {

    /**
     * What the dashboard's download section reads. {@code tag}/
     * {@code publishedAt} are null when GitHub is not consulted. Per asset:
     * {@code sizeBytes} is 0 and {@code sha256} is null when this server
     * genuinely cannot know them about a file it neither holds nor mirrors;
     * {@code url} is null when there is nothing to click, and then
     * {@code checksumUrl} is null with it.
     */
    public record LatestReleaseView(
            String appName,
            String tag,
            String version,
            String publishedAt,
            AssetView installer,
            AssetView portable,
            boolean available) {
    }

    /**
     * One released artefact. {@code checksumUrl} is the {@code .sha256}
     * beside the file on this server — the bytes a PowerShell
     * {@code Get-FileHash} comparison is checked against.
     */
    public record AssetView(
            String fileName,
            long sizeBytes,
            String sha256,
            String url,
            String checksumUrl) {
    }

    private final LatestReleaseService releases;
    private final DownloadCatalog catalog;
    private final GitHubReleaseMirror mirror;

    public WebLatestReleaseController(LatestReleaseService releases, DownloadCatalog catalog,
            GitHubReleaseMirror mirror) {
        this.releases = releases;
        this.catalog = catalog;
        this.mirror = mirror;
    }

    @GetMapping("/latest-release")
    public ResponseEntity<LatestReleaseView> latestRelease() {
        Optional<GitHubReleaseClient.Release> release = this.releases.latest();
        LatestReleaseView view = release.<LatestReleaseView>map(this::fromGitHub)
                .orElseGet(() -> fromLocal(this.catalog.installer(), this.catalog.portableZip()));
        // A release can change while a page is open; what the page said must
        // not fossilize in a browser cache or a proxy between them.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(view);
    }

    /** GitHub's answer first: the mirror makes every released artefact servable. */
    private LatestReleaseView fromGitHub(GitHubReleaseClient.Release release) {
        AssetView installer;
        AssetView portable;
        // The local artefacts ride along so that a file this server already
        // holds can be its URL even when a GitHub asset is unparsable — and
        // so that an odd release (no .msi at all) still describes whatever
        // the folder has.
        installer = assetOf(release.installer().orElse(null),
                this.catalog.installer().orElse(null), true);
        portable = assetOf(release.portableZip().orElse(null),
                this.catalog.portableZip().orElse(null), false);
        boolean available = serves(installer) || serves(portable);
        return new LatestReleaseView(
                this.catalog.appName(),
                release.tag(),
                release.version(),
                instantText(release.publishedAt()),
                installer,
                portable,
                available);
    }

    /** No GitHub: the folder is the whole truth, the same as it always was. */
    private LatestReleaseView fromLocal(Optional<DownloadCatalog.Artefact> installer,
            Optional<DownloadCatalog.Artefact> portable) {
        AssetView installerView = installer
                .map(artefact -> local(artefact))
                .orElseGet(() -> this.catalog.externalUrl()
                        .map(url -> new AssetView(null, 0L, null, url, null))
                        .orElse(null));
        AssetView portableView = portable
                .map(artefact -> local(artefact))
                .orElse(null);
        // The folder's own parsing of its newest file, or nothing — the word
        // "unknown" belongs to the server, not to a page.
        String version = installer
                .map(DownloadCatalog.Artefact::version)
                .filter(v -> !"unknown".equals(v))
                .orElse(null);
        return new LatestReleaseView(
                this.catalog.appName(), null, version, null,
                installerView, portableView,
                serves(installerView) || serves(portableView));
    }

    /**
     * One artefact, from whichever source knows it. GitHub's record wins for
     * provenance: sizes and checksums are what the release published, so
     * what a page shows is checkable against the release tags, not against
     * whatever copy reached this box first. The URL, though, is a promise
     * THIS server keeps: the download route — for a file already mirrored,
     * or whenever the mirror could fetch it on demand. With neither, only
     * an explicitly configured external link is offered (installer only,
     * like {@code download-info} before it), and the page shows a release
     * honestly without pretending there is a click.
     */
    private AssetView assetOf(GitHubReleaseClient.Asset remote, DownloadCatalog.Artefact local,
            boolean forInstaller) {
        if (remote != null) {
            String url = this.catalog.resolve(remote.name()).isPresent() || this.mirror.possible()
                    ? "/downloads/" + remote.name()
                    : (forInstaller ? this.catalog.externalUrl().orElse(null) : null);
            // A sidecar rides along only with a route THIS server serves;
            // an external link's checksum is whoever published that link's
            // business, and promising one would be a 404.
            String checksumUrl = url != null && url.startsWith("/downloads/")
                    ? url + ".sha256" : null;
            return new AssetView(
                    remote.name(),
                    remote.sizeBytes(),
                    remote.sha256().isEmpty() ? null : remote.sha256(),
                    url,
                    checksumUrl);
        }
        if (local != null) {
            return local(local);
        }
        return null;
    }

    /** A file the folder holds: its own route, size and published checksum. */
    private AssetView local(DownloadCatalog.Artefact artefact) {
        String url = "/downloads/" + artefact.fileName();
        return new AssetView(
                artefact.fileName(),
                artefact.sizeBytes(),
                artefact.sha256(),
                url,
                url + ".sha256");
    }

    private boolean serves(AssetView asset) {
        return asset != null && asset.url() != null;
    }

    private static String instantText(Instant at) {
        return at == null ? null : at.toString();
    }
}
