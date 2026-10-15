package com.example.signer.workflow;

import java.util.List;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.signing.PdfSigningService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads and writes the {@link WorkflowManifest} embedded inside a PDF as an
 * <b>embedded file</b> named {@code urn:signflow:workflow-manifest} attached
 * to the document catalog's EmbeddedFiles tree (the standards-compatible
 * "portfolio attachment" mechanism; no custom PDF operators, viewable in any
 * PDF reader's attachments panel).
 *
 * <p><b>Why embedded files:</b> document metadata (Info dict) is rewritten
 * by many PDF tools and is not part of any signature's coverage guarantees;
 * an embedded file is a first-class, schema-controlled object that PDFBox
 * serialises inside the incremental update — so when written during signing,
 * the newest revision's signature cryptographically covers the manifest
 * state it ships with (see {@code WorkflowSigningService}).
 *
 * <p><b>The customizer pattern:</b> {@link #applyTo} is invoked by the
 * signing service on the in-memory {@link PDDocument} <em>before</em>
 * {@code addSignature}/{@code saveIncremental}, so the manifest and the
 * signature land in the same revision and the chain still reaches EOF.
 * Nothing is ever appended outside a revision.
 *
 * <p>All failures are {@link SignerException} with workflow error codes;
 * raw PDFBox exceptions never escape.
 */
public final class WorkflowManifestStore {

    /** The name under which the manifest is embedded in the PDF catalog. */
    public static final String EMBEDDED_FILE_NAME = "urn:signflow:workflow-manifest";



    private static final Logger log = LoggerFactory.getLogger(WorkflowManifestStore.class);

    private WorkflowManifestStore() {
    }

    /**
     * Reads the raw manifest bytes from a PDF, or {@code null} when the
     * document carries no workflow manifest (normal PDFs keep working).
     * Malformed structures surface as exceptions from PDFBox; absence is
     * the only "no workflow" signal.
     */
    public static byte[] readRaw(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return readRawFromDocument(doc);
        }
    }

    /** As {@link #readRaw} but on an already-open document. */
    public static byte[] readRawFromDocument(PDDocument doc) throws IOException {
        PDDocumentNameDictionary names =
                doc.getDocumentCatalog().getNames();
        if (names == null) {
            return null;
        }
        PDEmbeddedFilesNameTreeNode embeddedFiles = names.getEmbeddedFiles();
        if (embeddedFiles == null) {
            return null;
        }
        // PDFBox 3 has no direct name lookup on the tree; walk it (flat in
        // practice for our single-entry manifest).
        Map<String, PDComplexFileSpecification> all = flatten(embeddedFiles);
        PDComplexFileSpecification spec = all.get(EMBEDDED_FILE_NAME);
        if (spec == null || spec.getEmbeddedFile() == null) {
            return null;
        }
        try (var is = spec.getEmbeddedFile().createInputStream()) {
            return is.readAllBytes();
        }
    }

    /**
     * Convenience: {@link #readRaw} + {@link WorkflowManifestCodec#tryParse}.
     * Never throws for content problems; returns {@code null} when no
     * manifest exists.
     */
    public static WorkflowManifestCodec.ParseResult tryRead(Path pdf) {
        try {
            byte[] raw = readRaw(pdf);
            if (raw == null) {
                return null;
            }
            return WorkflowManifestCodec.tryParse(raw);
        } catch (IOException e) {
            return new WorkflowManifestCodec.ParseResult(null,
                    new SignerException(ErrorCode.WORKFLOW_INVALID,
                            "The workflow embedded in this document could not be read", e));
        }
    }

    /**
     * Writes raw manifest bytes into an open document's embedded files,
     * replacing any previous manifest entry. Used both by the signing
     * customizer ({@link #writer}) and for seeding a fresh copy.
     */
    public static void write(PDDocument doc, byte[] bytes) throws IOException {
        applyTo(doc, bytes);
    }

    /**
     * Returns a customizer that writes {@code manifest} into the document's
     * embedded files, replacing any previous manifest entry (the signing
     * save then serialises it inside the new revision).
     */
    public static PdfSigningService.DocumentCustomizer writer(WorkflowManifest manifest) {
        byte[] bytes = WorkflowManifestCodec.serialize(manifest);
        return document -> applyTo(document, bytes);
    }

    /** Hook for {@code DefaultPdfSigningService}'s customizer overload. */
    private static void applyTo(PDDocument doc, byte[] bytes) throws IOException {
        PDDocumentCatalogBackedNames names = new PDDocumentCatalogBackedNames(doc);
        PDEmbeddedFilesNameTreeNode embeddedFiles = names.existingOrCreateNew();

        Map<String, PDComplexFileSpecification> current =
                new HashMap<>(flatten(embeddedFiles));
        current.remove(EMBEDDED_FILE_NAME);

        PDEmbeddedFile embeddedFile = new PDEmbeddedFile(
                doc, new ByteArrayInputStream(bytes));
        embeddedFile.setSize(bytes.length);
        embeddedFile.setModDate(new GregorianCalendar());
        embeddedFile.setCreationDate(new GregorianCalendar());

        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(EMBEDDED_FILE_NAME);
        spec.setEmbeddedFile(embeddedFile);
        spec.setFileDescription("SignFlow portable signing workflow manifest (schema v"
                + WorkflowManifest.CURRENT + ")");
        current.put(EMBEDDED_FILE_NAME, spec);

        // Replace the whole tree: name trees must be sorted for lookups, and
        // PDFBox's node API appends lazily — rebuilding with a sorted map
        // guarantees /Names order.
        PDEmbeddedFilesNameTreeNode replacement = new PDEmbeddedFilesNameTreeNode();
        replacement.setNames(current);
        names.setEmbeddedFiles(replacement);
        log.debug("Wrote workflow manifest ({} bytes) into PDF embedded files", bytes.length);
    }

    /* ---------------- helpers ---------------- */

    private static final class PDDocumentCatalogBackedNames {
        private final PDDocument doc;
        private PDDocumentNameDictionary names;

        PDDocumentCatalogBackedNames(PDDocument doc) {
            this.doc = doc;
            this.names = doc.getDocumentCatalog().getNames();
            if (this.names == null) {
                this.names = new PDDocumentNameDictionary(
                        doc.getDocumentCatalog());
                doc.getDocumentCatalog().setNames(this.names);
            }
        }

        PDEmbeddedFilesNameTreeNode existingOrCreateNew() {
            PDEmbeddedFilesNameTreeNode ef = names.getEmbeddedFiles();
            if (ef == null) {
                ef = new PDEmbeddedFilesNameTreeNode();
                names.setEmbeddedFiles(ef);
            }
            return ef;
        }

        void setEmbeddedFiles(PDEmbeddedFilesNameTreeNode ef) {
            names.setEmbeddedFiles(ef);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PDComplexFileSpecification> flatten(
            PDEmbeddedFilesNameTreeNode node) throws IOException {
        Map<String, PDComplexFileSpecification> out = new HashMap<>();
        if (node == null) {
            return out;
        }
        Map<String, PDComplexFileSpecification> direct = node.getNames();
        if (direct != null) {
            out.putAll(direct);
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids =
                node.getKids();
        if (kids != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> kid : kids) {
                out.putAll(flatten((PDEmbeddedFilesNameTreeNode) kid));
            }
        }
        return out;
    }
}
