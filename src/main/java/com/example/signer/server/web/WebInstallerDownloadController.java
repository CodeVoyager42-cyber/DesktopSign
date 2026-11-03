package com.example.signer.server.web;

import com.example.signer.server.downloads.DownloadCatalog;
import com.example.signer.server.downloads.GitHubReleaseClient;
import com.example.signer.server.downloads.GitHubReleaseMirror;
import com.example.signer.server.downloads.LatestReleaseService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The desktop installer, for a reader who is signed in.
 *
 * <h2>Why this exists beside {@code /downloads/**}</h2>
 *
 * <p>{@code DownloadController} serves the same bytes with no sign-in, and
 * deliberately: somebody invited to sign a document has no account yet and
 * cannot be asked to make one before installing the application that signs.
 * That route is unchanged and still public.
 *
 * <p>This one is the <b>website's</b> way to the installer, and it is behind
 * the session because it is on {@code /web/api/**} — which
 * {@code WebSecurityConfig} ends with
 * {@code requestMatchers(path("/web/api/**")).hasAuthority(ROLE_WEB_USER)}.
 * There is no new rule here and no annotation on the method: being under
 * that prefix <i>is</i> the authorisation, which is why a route added here
 * cannot be forgotten about. An anonymous caller gets the same 401 every
 * other website route gives, and the page sends them to sign in.
 *
 * <p>Two things follow from it being the signed-in route. The reader is
 * known, so the name the file is saved under can be the <b>stable</b> one
 * ({@code SignFlow-Setup.msi} by default) rather than the versioned name on
 * disk: a dashboard button that always saves the same name is what was
 * asked for, and the version is shown next to the button from
 * {@code /web/api/latest-release}. And the answer is {@code no-store},
 * because an authenticated response is not something a shared cache may
 * keep — unlike {@code /downloads/**}, whose versioned name makes it
 * immutable.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No path handling of its own. Which file is newest is
 * {@link DownloadCatalog#installer()} and turning a name into a path is
 * {@link DownloadCatalog#resolve}, so the allow-list and the
 * parent-directory check are the ones already proved by
 * {@code DownloadCatalogTests}. Nothing in any answer names a directory on
 * this machine: a file that is missing, unreadable or no longer there is a
 * 404 with a fixed body, the same as every other refusal.
 *
 * <p>The body is a {@link FileSystemResource}, so the bytes are streamed by
 * the container and never held in memory — a 200 MB installer costs a
 * buffer, not a heap — and {@code Range} is answered with a 206 by Spring's
 * own resource handling, so an interrupted download resumes.
 */
@RestController
@RequestMapping("/web/api")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebInstallerDownloadController {

    /**
     * What a configured save-name may look like.
     *
     * <p>It ends up inside a {@code Content-Disposition} header, so the one
     * thing that must be impossible is a quote or a newline closing the
     * header early and starting another. Rather than escape it, the shape is
     * restricted to what an installer is actually called — letters, digits,
     * dot, dash, underscore, ending in {@code .msi} — which has nothing in
     * it to escape.
     */
    private static final Pattern SAVE_AS = Pattern.compile("[A-Za-z0-9._-]{1,120}\\.msi");

    /** The registered media type for a Windows Installer package. */
    private static final MediaType MSI = MediaType.parseMediaType("application/x-msi");

    /** Every refusal, in the one wording, saying nothing about why. */
    private static final ErrorBody UNAVAILABLE = new ErrorBody("DOWNLOAD_UNAVAILABLE",
            "The installer is not available yet. Contact your administrator.");

    /** The same {@code code}/{@code message} shape the rest of /web/api uses. */
    public record ErrorBody(String code, String message) {
    }

    private final DownloadCatalog catalog;

    private final GitHubReleaseMirror mirror;

    private final LatestReleaseService releases;

    private final String saveAs;

    public WebInstallerDownloadController(DownloadCatalog catalog,
            GitHubReleaseMirror mirror, LatestReleaseService releases,
            @Value("${signature.remote-server.download.installer-name:"
                    + "${SIGNER_INSTALLER_FILENAME:SignFlow-Setup.msi}}") String saveAs) {
        this.catalog = catalog;
        this.mirror = mirror;
        this.releases = releases;
        String name = saveAs == null ? "" : saveAs.trim();
        // Set but unusable fails startup, the same choice DownloadCatalog
        // makes about its directory: a deployment that names the file badly
        // should hear about it now, not in a broken header months later.
        if (!SAVE_AS.matcher(name).matches()) {
            throw new IllegalStateException(
                    "signature.remote-server.download.installer-name must look like "
                            + "an installer file name (letters, digits, dot, dash, "
                            + "underscore, ending in .msi) but was: " + name);
        }
        this.saveAs = name;
    }

    /** The name a download is saved under, for the tests and for logging. */
    public String saveAsName() {
        return this.saveAs;
    }

    /**
     * {@code GET /web/api/desktop/installer} — the newest installer, as an
     * attachment.
     *
     * <p>A {@code HEAD} of it is how the page asks whether a download may
     * start before starting one: Spring answers by running this method and
     * dropping the body, so the status is authoritative — 200 to go ahead,
     * 401 to sign in first, 404 for nothing published, 429 for the rate
     * limit — and no bytes are read to find out.
     *
     * <p><b>A HEAD never mirrors</b>, for the reason
     * {@code DownloadController} does not either: an uptime monitor probing
     * the installer's absence is asking "is it there", not "make it be", and
     * fetching 200 MB from GitHub to answer a preflight would make the
     * button feel broken. When the folder is empty but the latest release
     * does publish an installer, a HEAD says 200 on that basis — "a GET of
     * this would give you the file" — and the GET is what actually mirrors.
     */
    @GetMapping("/desktop/installer")
    public ResponseEntity<?> installer(HttpServletRequest request) {
        Optional<Path> file = this.catalog.installer()
                .map(DownloadCatalog.Artefact::fileName)
                .flatMap(this.catalog::resolve);

        if (file.isEmpty()) {
            // Nothing published here yet. It may still be mirrorable, which
            // is a real state on a deployment that publishes from GitHub:
            // the folder fills on the first request, not at deploy time.
            Optional<String> mirrorable = mirrorableInstaller();
            if (mirrorable.isEmpty()) {
                return unavailable();
            }
            if ("HEAD".equals(request.getMethod())) {
                // Promise without performing: no length, because the file is
                // not here to measure, and a HEAD has no body to size.
                return attachment().build();
            }
            file = this.mirror.mirrorFile(mirrorable.get());
            if (file.isEmpty()) {
                // The fetch or its checksum verification failed. The mirror
                // has already logged why; to a reader it is simply absent.
                return unavailable();
            }
        }

        FileSystemResource body = new FileSystemResource(file.get());
        long length;
        try {
            length = body.contentLength();
        } catch (IOException e) {
            // The file went away between listing the folder and reading it.
            // What was asked for is not there: 404, not 500.
            return unavailable();
        }

        return attachment()
                .contentLength(length)
                .body((Resource) body);
    }

    /**
     * The headers every successful answer carries, whichever way the file
     * was found and whether or not there is a body to go with them.
     */
    private ResponseEntity.BodyBuilder attachment() {
        return ResponseEntity.ok()
                .contentType(MSI)
                // `attachment`, so the browser saves it instead of trying to
                // display it, under the stable name rather than the
                // versioned one on disk. The name is validated in the
                // constructor, so there is nothing here to inject into the
                // header.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + this.saveAs + "\"")
                // The type is declared, so nothing may second-guess it: an
                // installer sniffed as something else is a browser deciding
                // to run it.
                .header("X-Content-Type-Options", "nosniff")
                // Authenticated, so no shared cache may keep it — and the
                // stable name would make a stale copy survive a release.
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /**
     * The name of an installer this deployment could mirror on demand, when
     * its folder does not have one yet.
     *
     * <p>Empty whenever mirroring is off (no GitHub token, or no download
     * folder to put it in) or the latest release publishes no installer.
     * {@link LatestReleaseService#latest()} answers from its own cache and
     * swallows an unreachable GitHub, so this costs nothing per request and
     * cannot turn a download into a 500.
     */
    private Optional<String> mirrorableInstaller() {
        if (!this.mirror.possible()) {
            return Optional.empty();
        }
        return this.releases.latest()
                .flatMap(GitHubReleaseClient.Release::installer)
                .map(GitHubReleaseClient.Asset::name);
    }

    private static ResponseEntity<ErrorBody> unavailable() {
        return ResponseEntity.status(404)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(UNAVAILABLE);
    }
}
