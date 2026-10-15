package com.example.signer.ui.support;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * "Download signed PDF…": lets the user save a copy of a signed document
 * wherever they want. The bytes are copied as-is — a signed PDF must never
 * be rewritten, or its signatures would break.
 */
public final class SignedPdfExport {

    private SignedPdfExport() {
    }

    /**
     * Asks where to save, then copies {@code source} there.
     *
     * @param documentName the document's name, used for the suggested file name
     * @return the saved file, or empty when the user cancelled
     */
    public static Optional<Path> saveCopy(Window owner, Path source, String documentName)
            throws IOException {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save signed PDF");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"));
        chooser.setInitialFileName(suggestedName(documentName));
        File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return Optional.empty();
        }
        Path to = target.toPath();
        if (!to.getFileName().toString().toLowerCase().endsWith(".pdf")) {
            to = to.resolveSibling(to.getFileName() + ".pdf");
        }
        Files.copy(source, to, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(to);
    }

    /** "Contract.pdf" → "Contract-signed.pdf"; characters Windows forbids are replaced. */
    public static String suggestedName(String documentName) {
        String base = documentName == null || documentName.isBlank()
                ? "document" : documentName.trim();
        if (base.toLowerCase().endsWith(".pdf")) {
            base = base.substring(0, base.length() - 4);
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) {
            base = "document";
        }
        return base + "-signed.pdf";
    }
}
