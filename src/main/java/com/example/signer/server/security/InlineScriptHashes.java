package com.example.signer.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code sha256-} source expressions for the inline scripts in the
 * website's own pages, read from the pages this server actually serves.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The website is a Next.js static export, and every exported page carries
 * a handful of inline {@code <script>} blocks — the payload React needs to
 * take over the already-rendered markup
 * ({@code self.__next_f.push([...])}). They are not optional and there is no
 * setting that moves them into a file.
 *
 * <p>{@code script-src 'self'} does not permit an inline script, so without
 * this the pages would load their code and then fail to start. The three ways
 * out are not equal:
 *
 * <ul>
 *   <li>{@code 'unsafe-inline'} would permit <b>every</b> inline script,
 *       including one an attacker managed to inject. That is the protection
 *       the policy exists for, traded away for a build tool's convenience.</li>
 *   <li>A per-response <b>nonce</b> is the usual answer, and it needs the
 *       server to rewrite each page as it serves it — inserting the nonce into
 *       every script tag. That means buffering and editing HTML on the way
 *       out, and the pages are static files served by Spring's resource
 *       handler, which is the reason they are cheap and cacheable.</li>
 *   <li><b>Hashes</b>, which is this. Each permitted script is named by the
 *       hash of its exact contents, so a script that was not in the build does
 *       not run, no matter how it got into the page.</li>
 * </ul>
 *
 * <h2>Read at startup, not written down</h2>
 *
 * <p>The hashes are computed from the exported HTML on the classpath when the
 * application starts, rather than kept in a file beside the policy. A list
 * somebody has to regenerate is a list that will be stale exactly once — after
 * a frontend change, when the pages silently stop working — so this reads what
 * is really there. It costs one pass over about ten small files, once.
 *
 * <p>The cost of the approach, stated plainly: the policy header grows by
 * roughly 50 bytes per distinct inline script (about thirty of them, so
 * around 1.5 KB), and it is sent on every response. That is the price of not
 * weakening {@code script-src}.
 */
final class InlineScriptHashes {

    private static final Logger log = LoggerFactory.getLogger(InlineScriptHashes.class);

    /** Where the exported website lives on the classpath. */
    private static final String PAGES = "classpath:/static/**/*.html";

    /**
     * An inline script: a {@code <script>} with no {@code src}, and its
     * contents. {@code DOTALL} because the payload spans lines, and the lazy
     * body so two scripts in one page do not become one match.
     */
    private static final Pattern INLINE_SCRIPT = Pattern.compile(
            "<script(?![^>]*\\ssrc=)([^>]*)>(.*?)</script>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * A script element the browser does not execute — an import map, or a
     * block of JSON a framework reads back out of the document. The policy
     * does not gate those, so hashing them would only pad the header.
     */
    private static final Pattern NOT_EXECUTABLE = Pattern.compile(
            "type\\s*=\\s*[\"']?(application/json|application/ld\\+json|importmap)",
            Pattern.CASE_INSENSITIVE);

    private InlineScriptHashes() {
    }

    /**
     * Every distinct inline script in the website, as CSP source
     * expressions, sorted so the header is identical from one start to the
     * next. Empty when there is no exported website on the classpath, in
     * which case the policy is simply {@code 'self'} and nothing is lost.
     */
    static List<String> scan() {
        Set<String> hashes = new TreeSet<>();
        try {
            Resource[] pages = new PathMatchingResourcePatternResolver()
                    .getResources(PAGES);
            for (Resource page : pages) {
                hashes.addAll(hashesIn(read(page)));
            }
            log.info("Content-Security-Policy: {} inline script hash(es) from {} page(s)"
                    + " of the exported website", hashes.size(), pages.length);
        } catch (IOException e) {
            // A policy without the hashes refuses the website's own scripts,
            // which is visible immediately and safe. Guessing at
            // 'unsafe-inline' instead would be neither.
            log.warn("Could not read the exported website to hash its inline scripts;"
                    + " the policy will not permit them: {}", e.toString());
        }
        return List.copyOf(hashes);
    }

    /** The hashes of the executable inline scripts in one page's HTML. */
    private static Set<String> hashesIn(String html) {
        Set<String> hashes = new TreeSet<>();
        Matcher match = INLINE_SCRIPT.matcher(html);
        while (match.find()) {
            String attributes = match.group(1);
            if (NOT_EXECUTABLE.matcher(attributes).find()) {
                continue;
            }
            String body = match.group(2);
            if (body.isEmpty()) {
                continue;
            }
            hashes.add("'sha256-" + sha256(body) + "'");
        }
        return hashes;
    }

    /**
     * The hash of a script's contents, exactly as a browser computes it: the
     * bytes between the tags, UTF-8, base64 — no trimming. A browser hashes
     * what is in the document, so anything done to the text here would make
     * every hash miss.
     */
    private static String sha256(String script) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest
                    .getInstance("SHA-256")
                    .digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM",
                    impossible);
        }
    }

    private static String read(Resource page) throws IOException {
        try (InputStream in = page.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
