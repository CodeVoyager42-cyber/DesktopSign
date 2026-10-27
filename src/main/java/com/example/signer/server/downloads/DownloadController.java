package com.example.signer.server.downloads;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Hands over the desktop installer.
 *
 * <p><b>Public, with no sign-in</b>, and that is the point: somebody who was
 * invited to sign a document has no account yet, and telling them to create
 * one before they can install the application that signs would be a circle.
 * There is nothing here to protect — it is the same file everyone gets — so
 * the limit on it is a rate limit per address ({@code DownloadRateLimitFilter}),
 * not an authorisation.
 *
 * <p>Every path decision is {@link DownloadCatalog#resolve}: this class
 * turns an empty answer into a 404 and a present one into bytes. A refusal
 * is always 404 and always the same, whether the name was not on the
 * allow-list, pointed outside the directory, was a directory, or simply does
 * not exist — and never a 500, because the catalogue does not throw.
 *
 * <p>Range requests work because the body is a {@link FileSystemResource}:
 * Spring's resource handling answers {@code Range} with a 206 and sets
 * {@code Accept-Ranges} itself. For a 200 MB installer on a hotel connection
 * that is the difference between resuming and starting again.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DownloadController {

    private final DownloadCatalog catalog;

    public DownloadController(DownloadCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/downloads/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable String fileName) {
        Optional<Path> file = this.catalog.resolve(fileName);
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = file.get();
        FileSystemResource body = new FileSystemResource(path);
        long length;
        try {
            length = body.contentLength();
        } catch (java.io.IOException e) {
            // The file went away between resolving and reading it. A reader
            // asked for something that is no longer there: 404, not 500.
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(contentTypeOf(fileName))
                .contentLength(length)
                // `attachment` so a browser saves the installer instead of
                // trying to display it, and the name it is saved under is the
                // allow-listed one — it cannot contain a quote or a newline,
                // so there is nothing here to inject into the header.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                // The type is declared, so nothing may second-guess it: an
                // installer sniffed as something else is a browser deciding
                // to run it.
                .header("X-Content-Type-Options", "nosniff")
                // A release artefact at a given name never changes; a new
                // release has a new version in its name.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400, immutable")
                .body(body);
    }

    private static MediaType contentTypeOf(String fileName) {
        if (fileName.endsWith(".sha256")) {
            return MediaType.TEXT_PLAIN;
        }
        if (fileName.endsWith(".zip")) {
            return MediaType.parseMediaType("application/zip");
        }
        // The registered type for a Windows Installer package.
        return MediaType.parseMediaType("application/x-msi");
    }
}
