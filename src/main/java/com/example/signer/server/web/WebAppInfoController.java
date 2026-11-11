package com.example.signer.server.web;

import com.example.signer.server.downloads.DownloadCatalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * The few facts about this deployment that a page cannot know for itself.
 *
 * <p>Both routes here answer the same question — "where does somebody get the
 * desktop application?" — at two levels of detail, and both are backed by the
 * one {@link DownloadCatalog} so they cannot disagree:
 *
 * <ul>
 *   <li>{@code GET /web/api/app-info} — behind the session, as it has been
 *       since Phase F: the application's name and a link, which is all the
 *       hand-off screen's fallback needs.</li>
 *   <li>{@code GET /web/api/download-info} — <b>public</b>, for the
 *       {@code /download} page: which file, how big, its checksum, and
 *       whether there is anything to download at all. Public because the page
 *       is: an invited signer has no account yet and still needs the
 *       application. Rate limited per address
 *       ({@code DownloadRateLimitFilter}).</li>
 * </ul>
 *
 * <p>The website is a static export, so it cannot read a property that is
 * only decided when the server is deployed — which is why these exist at all.
 */
@RestController
@RequestMapping("/web/api")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebAppInfoController {

    /** What the website may know about the application it hands off to. */
    public record AppInfoView(String appName, String downloadUrl) {
    }

    /**
     * What the download page shows.
     *
     * <p>{@code available} is the single thing the page branches on, so it
     * never has to work out whether a null url means "not published yet" or
     * "published elsewhere". {@code sha256} and {@code sizeBytes} are null
     * and 0 for an external link, because this server cannot know them about
     * a file it does not hold.
     */
    public record DownloadInfoView(
            String appName,
            String version,
            String fileName,
            long sizeBytes,
            String sha256,
            String url,
            String portableUrl,
            String portableFileName,
            long portableSizeBytes,
            boolean available) {
    }

    private final DownloadCatalog catalog;

    public WebAppInfoController(DownloadCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/app-info")
    public ResponseEntity<AppInfoView> appInfo() {
        return ResponseEntity.ok(new AppInfoView(
                this.catalog.appName(), primaryUrl().orElse(null)));
    }

    @GetMapping("/download-info")
    public ResponseEntity<DownloadInfoView> downloadInfo() {
        Optional<DownloadCatalog.Artefact> installer = this.catalog.installer();
        Optional<DownloadCatalog.Artefact> portable = this.catalog.portableZip();
        // The installer is the primary offer, and the portable build stands in
        // for it where no MSI could be produced (jpackage needs WiX for one).
        // A folder holding only the zip is a published release, so the facts
        // the page shows describe whichever file is actually served.
        Optional<DownloadCatalog.Artefact> primary = installer.isPresent() ? installer : portable;
        Optional<String> url = primaryUrl();

        DownloadInfoView view = new DownloadInfoView(
                this.catalog.appName(),
                primary.map(DownloadCatalog.Artefact::version).orElse(null),
                primary.map(DownloadCatalog.Artefact::fileName).orElse(null),
                primary.map(DownloadCatalog.Artefact::sizeBytes).orElse(0L),
                primary.map(DownloadCatalog.Artefact::sha256).orElse(null),
                url.orElse(null),
                // Offered as the alternative only when it is the alternative: a
                // page whose one button already leads to the zip must not offer
                // the same file a second time beside it.
                installer.isPresent()
                        ? portable.map(a -> "/downloads/" + a.fileName()).orElse(null)
                        : null,
                installer.isPresent()
                        ? portable.map(DownloadCatalog.Artefact::fileName).orElse(null)
                        : null,
                installer.isPresent()
                        ? portable.map(DownloadCatalog.Artefact::sizeBytes).orElse(0L)
                        : 0L,
                url.isPresent());

        // The answer changes when a release is published, which is a file
        // appearing in a directory — not something a cache can be told about.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(view);
    }

    /**
     * Where the application is: this server when a release is published here —
     * the installer, or the portable build when that is all the release has —
     * the configured external link when there is one, and nowhere otherwise.
     *
     * <p>The local file wins on purpose. A deployment that has published a
     * release has said where the application is more recently, and more
     * precisely, than a URL set once in the environment.
     */
    private Optional<String> primaryUrl() {
        Optional<String> local = this.catalog.installer()
                .or(this.catalog::portableZip)
                .map(artefact -> "/downloads/" + artefact.fileName());
        return local.isPresent() ? local : this.catalog.externalUrl();
    }
}
