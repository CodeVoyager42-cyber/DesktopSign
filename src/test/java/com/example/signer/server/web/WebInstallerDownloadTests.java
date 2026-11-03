package com.example.signer.server.web;

import com.example.signer.server.downloads.DownloadCatalog;
import com.example.signer.server.downloads.GitHubReleaseClient;
import com.example.signer.server.downloads.GitHubReleaseMirror;
import com.example.signer.server.downloads.LatestReleaseService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code GET /web/api/desktop/installer} answers, and what it never
 * says.
 *
 * <p>Driven through the controller directly, for the reason
 * {@code DownloadInfoTests} gives: what is under test is which file is
 * chosen, the headers a browser needs and the shape of a refusal, and three
 * Spring contexts would be three minutes to say the same thing. The path
 * rules themselves belong to {@code DownloadCatalogTests}, which this route
 * goes through unchanged.
 *
 * <p><b>The authorisation is deliberately not tested here</b>, because there
 * is nothing on this class to test: the route is protected by being under
 * {@code /web/api/**}, which {@code WebSecurityConfig} ends with a
 * {@code hasAuthority(ROLE_WEB_USER)} rule. Asserting the 401 means booting
 * the website's filter chain, which needs PostgreSQL — the same reason
 * {@code DownloadHttpTests} is skipped without one.
 */
class WebInstallerDownloadTests {

    @TempDir
    Path published;

    /**
     * The controller, with mirroring off: an empty GitHub token disables the
     * client, so {@code possible()} is false and no test here reaches the
     * network. The mirror's own behaviour is covered by its own tests.
     */
    private WebInstallerDownloadController controller(String dir, String saveAs) {
        DownloadCatalog catalog = new DownloadCatalog(dir, "", "SignFlow");
        LatestReleaseService releases =
                new LatestReleaseService("https://api.github.invalid", "owner/repo", "",
                        Duration.ofHours(1));
        GitHubReleaseMirror mirror = new GitHubReleaseMirror(catalog, releases,
                new GitHubReleaseClient("https://api.github.invalid", "owner/repo", ""));
        return new WebInstallerDownloadController(catalog, mirror, releases, saveAs);
    }

    private static MockHttpServletRequest get() {
        return new MockHttpServletRequest("GET", "/web/api/desktop/installer");
    }

    private static MockHttpServletRequest head() {
        return new MockHttpServletRequest("HEAD", "/web/api/desktop/installer");
    }

    @Test
    void aPublishedInstallerIsServedUnderTheStableName() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");

        ResponseEntity<?> answer =
                controller(this.published.toString(), "SignFlow-Setup.msi").installer(get());

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        // The stable name, NOT the versioned one on disk: that is the whole
        // difference between this route and /downloads/<file>.
        assertThat(answer.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"SignFlow-Setup.msi\"");
        assertThat(answer.getHeaders().getContentType()).hasToString("application/x-msi");
        assertThat(answer.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        // Authenticated: no shared cache may keep it, and the stable name
        // would otherwise let a stale copy survive a release.
        assertThat(answer.getHeaders().getCacheControl()).isEqualTo("no-store");
        // Declared, so the browser can show progress and a size.
        assertThat(answer.getHeaders().getContentLength())
                .isEqualTo("installer bytes".length());
        // Streamed from the file rather than read into the answer.
        assertThat(answer.getBody()).isInstanceOf(Resource.class);
    }

    @Test
    void theConfiguredNameIsWhatTheBrowserSaves() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");

        ResponseEntity<?> answer =
                controller(this.published.toString(), "DigitalSigner-Setup.msi").installer(get());

        assertThat(answer.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"DigitalSigner-Setup.msi\"");
    }

    @Test
    void nothingPublishedIsAFourOhFourAndNotAFault() {
        ResponseEntity<?> answer = controller(this.published.toString(), "SignFlow-Setup.msi")
                .installer(get());

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(answer.getBody())
                .isInstanceOf(WebInstallerDownloadController.ErrorBody.class);
        var body = (WebInstallerDownloadController.ErrorBody) answer.getBody();
        assertThat(body.code()).isEqualTo("DOWNLOAD_UNAVAILABLE");
        // Nothing in a refusal names a directory on this machine.
        assertThat(body.message()).doesNotContain(this.published.toString());
    }

    @Test
    void anUnconfiguredDeploymentIsTheSameFourOhFour() {
        ResponseEntity<?> answer = controller("", "SignFlow-Setup.msi").installer(get());

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aHeadOfNothingPublishedRefusesToo() {
        // The page's preflight: the same status a GET would give, so the
        // button can decide without starting a download.
        ResponseEntity<?> answer = controller(this.published.toString(), "SignFlow-Setup.msi")
                .installer(head());

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aHeadOfAPublishedInstallerPromisesIt() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");

        ResponseEntity<?> answer = controller(this.published.toString(), "SignFlow-Setup.msi")
                .installer(head());

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"SignFlow-Setup.msi\"");
    }

    @Test
    void aPortableZipAloneIsNotAnInstaller() throws IOException {
        // The zip is still offered by the public route; this one serves the
        // .msi or nothing, so a folder holding only a zip is a 404.
        Files.writeString(this.published.resolve("SignFlow-0.3.1-windows.zip"), "zip bytes");

        ResponseEntity<?> answer = controller(this.published.toString(), "SignFlow-Setup.msi")
                .installer(get());

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aSaveNameThatCouldBreakTheHeaderFailsStartup() {
        // It goes into a Content-Disposition header, so the shape is
        // restricted rather than escaped — and a deployment that gets it
        // wrong hears about it at once, like a misconfigured download folder.
        assertThatThrownBy(() -> controller(this.published.toString(), "bad\";name.msi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("installer-name");

        assertThatThrownBy(() -> controller(this.published.toString(), "installer.exe"))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> controller(this.published.toString(), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void surroundingSpaceInTheConfiguredNameIsForgiven() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");

        ResponseEntity<?> answer =
                controller(this.published.toString(), "  SignFlow-Setup.msi  ").installer(get());

        assertThat(answer.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"SignFlow-Setup.msi\"");
    }
}
