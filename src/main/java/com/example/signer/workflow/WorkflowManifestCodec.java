package com.example.signer.workflow;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Serialises {@link WorkflowManifest} to strict JSON and parses it back from
 * <b>untrusted</b> input (the bytes live inside a PDF anyone can edit).
 *
 * <p>Parsing is deliberately hardened:
 *
 * <ul>
 *   <li>unknown top-level or per-signer fields are ignored (forward
 *       compatibility), but a missing or non-object JSON document is
 *       rejected</li>
 *   <li>size-bounded input (256 KiB) — a manifest never needs to be larger;
 *       embedded-file attacks with gigabyte payloads die here</li>
 *   <li>a JSON {@code null} document (the literal four bytes {@code null})
 *       is rejected rather than parsed into an empty record</li>
 *   <li>all domain validation ({@code WorkflowManifest}/{@code WorkflowSignerStep}
 *       compact constructors) runs after binding, so semantically invalid
 *       manifests fail with a {@link SignerException}, not a
 *       {@link NullPointerException} somewhere else</li>
 *   <li>schema version is checked before binding semantics: a newer version
 *       yields {@code WORKFLOW_UNSUPPORTED_VERSION} with a clear message,
 *       and the parsed manifest can still be surfaced read-only</li>
 * </ul>
 *
 * <p>The reader is shared and configured once; {@link ObjectMapper} is
 * thread-safe after configuration.
 */
public final class WorkflowManifestCodec {

    /** Hard upper bound for manifest bytes (schema v1 needs &lt; 4 KiB). */
    public static final int MAX_MANIFEST_BYTES = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private WorkflowManifestCodec() {
    }

    /** Serialises the manifest as compact UTF-8 JSON. */
    public static byte[] serialize(WorkflowManifest manifest) {
        try {
            return MAPPER.writeValueAsBytes(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("Workflow manifest could not be serialised", e);
        }
    }

    /**
     * Parses and validates a manifest from untrusted bytes.
     *
     * @throws SignerException with
     *         {@code WORKFLOW_INVALID} for malformed/too-large/empty input or
     *         domain-invalid content, or
     *         {@code WORKFLOW_UNSUPPORTED_VERSION} for a schema version newer
     *         than {@link WorkflowManifest#CURRENT}
     */
    public static WorkflowManifest deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Workflow manifest is empty");
        }
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Workflow manifest is too large to be valid");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Workflow manifest is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Workflow manifest must be a JSON object");
        }
        JsonNode versionNode = root.get("workflowManifestVersion");
        if (versionNode == null || !versionNode.canConvertToInt()) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Workflow manifest has no valid schema version");
        }
        int version = versionNode.asInt();
        if (version > WorkflowManifest.CURRENT) {
            throw new SignerException(ErrorCode.WORKFLOW_UNSUPPORTED_VERSION,
                    "This workflow was created by a newer version of the application"
                            + " (manifest schema " + version + ", supported: "
                            + WorkflowManifest.CURRENT + ")");
        }
        if (version < WorkflowManifest.MIN_SUPPORTED) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Workflow manifest schema version " + version
                            + " is no longer supported");
        }
        try {
            return MAPPER.readValue(bytes, WorkflowManifest.class);
        } catch (IOException e) {
            // Jackson wraps record-constructor validation failures; surface
            // the domain message ("signer order must be 1..N without gaps",
            // fingerprint null-checks, …) instead of a generic wrapping text.
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof IllegalArgumentException iae) {
                    throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                            "Workflow manifest content is invalid: " + iae.getMessage(), e);
                }
                cause = cause.getCause();
            }
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "Workflow manifest content is invalid", e);
        }
    }

    /**
     * Best-effort parse for inspection paths that must not throw (e.g. the
     * open-a-document UI deciding between "no workflow", "workflow" and
     * "broken workflow"). Returns {@code null} when {@code bytes} is
     * {@code null}/empty — i.e. no manifest data at all — a successful
     * {@link ParseResult} for a valid manifest, or a failed one describing
     * why the bytes could not be used. Never throws for content problems.
     */
    public static ParseResult tryParse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return new ParseResult(deserialize(bytes), null);
        } catch (SignerException e) {
            return new ParseResult(null, e);
        }
    }

    /** Result of a non-throwing parse attempt. Exactly one side is non-null. */
    public record ParseResult(WorkflowManifest manifest, SignerException failure) {
        public boolean ok() {
            return manifest != null;
        }
    }

    /** Debug/fixture helper: pretty JSON of a manifest. */
    public static String toPrettyJson(WorkflowManifest manifest) {
        try {
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

