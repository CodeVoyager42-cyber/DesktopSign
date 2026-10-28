package com.example.signer.server.downloads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the latest published release of this project from the GitHub API,
 * and its assets.
 *
 * <p>The source repository is private, so nothing here works anonymously:
 * the token comes from the deployment environment ({@code GITHUB_API_TOKEN}).
 * It never leaves this class — it reaches no log, no page and no download
 * URL, and it is stripped before following GitHub's redirect to a release
 * asset on the user-content host, which is a signed link needing no
 * credential at all.
 *
 * <p>Failures are modelled deliberately: a genuine "no release exists" is
 * {@link Optional#empty()}, while everything that means "the answer cannot
 * be obtained right now" — unreachable, rate limited, server error,
 * malformed — is {@link Unavailable}. A caller holding a stale answer keeps
 * serving it; a caller without one reports nothing rather than a guess.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GitHubReleaseClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubReleaseClient.class);

    /** The API contract these calls were written against. */
    private static final String GITHUB_API_VERSION = "2022-11-28";

    private static final String JSON_ACCEPT = "application/vnd.github+json";
    private static final String OCTET_STREAM = "application/octet-stream";

    private static final String AUTHORIZATION = "Authorization";

    /** A digest record is spelled {@code sha256:<64 hex characters>}. */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    /**
     * The GitHub release an installation should be serving.
     *
     * <p>{@code version} is the tag with its leading {@code v} removed — the
     * exact string the artefact names carry ({@code SignFlow-<version>.msi}),
     * and what the page shows after "Latest Version:". Only names on the
     * download allow-list are usable, so {@link #installer()} and
     * {@link #portableZip()} filter those before any caller can see them.
     */
    public record Release(String tag, String version, Instant publishedAt, List<Asset> assets) {

        /** The asset published under exactly this file name, if there is one. */
        public Optional<Asset> asset(String fileName) {
            return this.assets.stream().filter(a -> a.name().equals(fileName)).findFirst();
        }

        /** The one released installer ({@code .msi}) with an allowed name. */
        public Optional<Asset> installer() {
            return endingIn(".msi");
        }

        /** The one released portable zip ({@code -windows.zip}) with an allowed name. */
        public Optional<Asset> portableZip() {
            return endingIn("-windows.zip");
        }

        private Optional<Asset> endingIn(String suffix) {
            // Real releases hold exactly one of each; picking the
            // lexicographically smallest makes any oddity deterministic
            // instead of order-dependent.
            return this.assets.stream()
                    .filter(a -> a.name().endsWith(suffix))
                    .filter(a -> DownloadCatalog.isAllowed(a.name()))
                    .min(java.util.Comparator.comparing(Asset::name));
        }
    }

    /**
     * One released file, with everything publishing needs to know about it.
     * {@code sha256} comes from GitHub's own {@code digest} record and is
     * GitHub's attestation, never this server's calculation — which is the
     * point: bytes mirroring into the download folder are checked against a
     * hash published independently of them.
     */
    public record Asset(long id, String name, long sizeBytes, String sha256) {
    }

    /**
     * GitHub was reached, but not with a usable answer — keep serving the
     * last known release instead of treating this as a fact. Never carries
     * a request's input: what failed, not what was asked.
     */
    public static final class Unavailable extends RuntimeException {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // No automatic redirects: the asset hop must not carry the
            // Authorization header to a third-party host. One explicit
            // follow, without credentials, is easier to reason about than a
            // redirect policy — and easier to limit to exactly one hop.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper json = new ObjectMapper();

    private final String apiBase;
    private final String repository;
    private final String token;

    public GitHubReleaseClient(
            @Value("${signature.remote-server.download.github.api-base-url:https://api.github.com}")
            String apiBase,
            @Value("${signature.remote-server.download.github.repository:CodeVoyager42-cyber/DesktopSign}")
            String repository,
            @Value("${signature.remote-server.download.github.token:}")
            String token) {
        this.apiBase = trimmed(apiBase);
        this.repository = trimmed(repository);
        this.token = token == null ? "" : token.trim();
        if (!this.enabled()) {
            log.info("GitHub release mirror is not configured (no GITHUB_API_TOKEN):"
                    + " the download folder remains the only source of release files.");
        }
    }

    /** Whether this deployment can ask GitHub anything at all. */
    public boolean enabled() {
        return !this.token.isEmpty();
    }

    /**
     * The latest published release, or empty when there genuinely is none.
     *
     * @throws Unavailable when GitHub cannot be reached or answered
     *         wrongly — the caller keeps whatever it already knows.
     */
    public Optional<Release> latest() {
        if (!this.enabled()) {
            return Optional.empty();
        }
        URI uri = URI.create(this.apiBase + "/repos/" + this.repository + "/releases/latest");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", JSON_ACCEPT)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .header(AUTHORIZATION, "Bearer " + this.token)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response =
                    this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                // Asked and answered: the repository has no published release.
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new Unavailable("GitHub answered " + response.statusCode()
                        + " for the latest release", null);
            }
            JsonNode root = this.json
                    .readTree(new String(response.body(), StandardCharsets.UTF_8));
            String tag = root.path("tag_name").asText("");
            if (tag.isBlank()) {
                throw new Unavailable("The latest GitHub release arrived without a tag", null);
            }
            return Optional.of(new Release(
                    tag,
                    tag.startsWith("v") ? tag.substring(1) : tag,
                    instant(root.path("published_at").asText(null)),
                    assets(root.path("assets"))));
        } catch (Unavailable e) {
            // Already the modelled failure — do not wrap it again.
            throw e;
        } catch (IOException | RuntimeException e) {
            // IOException covers the wire and every Jackson parse (its
            // parsing exceptions extend it); RuntimeException covers any
            // other way an answer can disappoint. The reason is re-thrown,
            // never logged with more than the fact.
            throw new Unavailable("The GitHub releases endpoint could not be read", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while reading the GitHub release", e);
        }
    }

    /**
     * Streams one released asset into {@code destination}, verifying the
     * bytes against the digest GitHub published for it and against its own
     * declared size. Returns whether the file on {@code destination} is now
     * complete and verified — on any failure the caller must not keep it
     * (this method does not delete; the caller owns the temporary file).
     */
    public boolean downloadAsset(Asset asset, Path destination) {
        if (!this.enabled() || asset.id() < 0 || !SHA256_HEX.matcher(asset.sha256()).matches()) {
            return false;
        }
        URI uri = URI.create(this.apiBase + "/releases/assets/" + asset.id());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Accept", OCTET_STREAM)
                .header(AUTHORIZATION, "Bearer " + this.token)
                .GET()
                .build();
        HttpResponse<InputStream> response = null;
        try {
            response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            response = followed(response, response.uri());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (InputStream in = response.body();
                 var out = java.nio.file.Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    read += n;
                    // The stream a compromised source could hand us has this
                    // exact ceiling: GitHub published the size, the folder
                    // should never inherit more than it.
                    if (read > asset.sizeBytes()) {
                        log.warn("A release asset streamed larger than it was declared");
                        return false;
                    }
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (read != asset.sizeBytes()) {
                log.warn("A release asset streamed shorter than it was declared");
                return false;
            }
            // The lowered hex is what the digest record carries; comparing
            // digests by constant-time comparison, like every secret, for
            // form's sake.
            byte[] expected = HexFormat.of()
                    .parseHex(asset.sha256().toLowerCase(java.util.Locale.ROOT));
            return MessageDigest.isEqual(digest.digest(), expected);
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers any Jackson-free parse nonsense and,
            // with it, every way a response can disappoint. The reason is
            // logged by what owns the decision; the caller answers 404.
            log.warn("The GitHub release asset could not be mirrored: {}",
                    e.getClass().getSimpleName());
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("The GitHub release asset could not be mirrored: interrupted");
            return false;
        } finally {
            if (response.body() != null) {
                try {
                    response.body().close();
                } catch (IOException e) {
                    log.debug("Closing a half-read asset stream failed: {}",
                            e.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * One redirect hop, without credentials. GitHub's asset answer points at
     * a time-limited signed link on the user-content host, which needs no
     * Authorization at all — carrying the token there would give a
     * third-party header to legitimate traffic at best. Exactly one hop:
     * anything further is a failure of the endpoint's own contract.
     */
    private HttpResponse<InputStream> followed(HttpResponse<InputStream> response, URI from)
            throws IOException, InterruptedException {
        int status = response.statusCode();
        if (status < 300 || status > 399) {
            return response;
        }
        String location = response.headers().firstValue("Location").orElse("");
        if (location.isBlank()) {
            throw new IOException(status + " from GitHub with no Location header");
        }
        URI next = from == null ? URI.create(location) : from.resolve(location);
        HttpRequest follow = HttpRequest.newBuilder(next)
                .timeout(Duration.ofSeconds(120))
                .header("Accept", OCTET_STREAM)
                // Deliberately no Authorization header on the hop out.
                .GET()
                .build();
        HttpResponse<InputStream> second =
                this.http.send(follow, HttpResponse.BodyHandlers.ofInputStream());
        if (second.statusCode() >= 300 && second.statusCode() <= 399) {
            closeQuietly(second.body());
            throw new IOException("GitHub redirected the asset more than once");
        }
        return second;
    }

    private static List<Asset> assets(JsonNode list) {
        List<Asset> assets = new ArrayList<>();
        for (JsonNode node : list) {
            // digest arrives as "sha256:<hex>"; keep only a well-formed hex
            // tail, so an unverifiable asset can never be mirrored.
            String digest = node.path("digest").asText("");
            String sha256 = digest.startsWith("sha256:")
                    && SHA256_HEX.matcher(digest.substring(7)).matches()
                    ? digest.substring(7).toLowerCase(java.util.Locale.ROOT)
                    : "";
            assets.add(new Asset(node.path("id").asLong(-1),
                    node.path("name").asText(""),
                    node.path("size").asLong(0),
                    sha256));
        }
        return List.copyOf(assets);
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            // A release whose clock we cannot read is still a release; the
            // page shows the tag and nothing dated.
            return null;
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** Busywork: half-read streams must stop owning a socket. */
    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException e) {
            log.debug("Closing an asset stream failed: {}", e.getClass().getSimpleName());
        }
    }
}
