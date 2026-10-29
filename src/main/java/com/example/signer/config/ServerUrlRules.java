package com.example.signer.config;

import java.net.URI;
import java.util.Locale;

/**
 * What a coordination-server address is allowed to look like.
 *
 * <p>One place, because the same address is written in three of them and a
 * rule that holds in only two is worse than no rule: {@code scripts/package.sh}
 * checks it at build time before baking it into an installer (in bash, so that
 * copy is a deliberate mirror of this one - keep them together), the IT
 * override in {@link DesktopSettingsFile} checks it at start-up, and
 * {@link ProductionSafetyValidator} has the last word on the transport.
 *
 * <p>The rules, and why each one is here:
 * <ul>
 *   <li><b>{@code https}</b>, unless plain HTTP is explicitly allowed for a
 *       local build. Credentials, tokens and whole documents travel this
 *       address.</li>
 *   <li><b>A host.</b> {@code https:///} parses, and reaches nothing.</li>
 *   <li><b>No user information.</b> {@code https://user:pw@host} would put a
 *       credential in a build argument, a settings file and the log line that
 *       reports the address.</li>
 *   <li><b>No path, no query, no fragment, and no trailing slash.</b> This is
 *       a base address that request paths are appended to, so
 *       {@code .../api} or a trailing {@code /} silently produces
 *       {@code //api/...} or {@code /api/api/...} at the far end. Rejecting it
 *       at the point it is written beats debugging a 404 from an installer
 *       that has already shipped.</li>
 * </ul>
 */
public final class ServerUrlRules {

    private ServerUrlRules() {
    }

    /**
     * Why {@code raw} is not usable as a coordination-server address, or
     * {@code null} when it is.
     *
     * @param raw            the configured address, as written
     * @param allowPlainHttp whether {@code http://} is acceptable (a local
     *                       build or test deployment; never a shipped one)
     */
    public static String problem(String raw, boolean allowPlainHttp) {
        if (raw == null || raw.isBlank()) {
            return "no coordination server address is set";
        }
        String url = raw.trim();
        if (!url.equals(raw)) {
            return "the address has leading or trailing whitespace: '" + raw + "'";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return "the address is not a valid URL: '" + url + "'";
        }
        if (!uri.isAbsolute()) {
            return "the address needs a scheme, e.g. https://signflow.example.com"
                    + " (got '" + url + "')";
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowPlainHttp && "http".equals(scheme))) {
            return allowPlainHttp
                    ? "the address must use https:// or http:// (got '" + url + "')"
                    : "the address must use https:// (got '" + url + "')";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "the address has no host: '" + url + "'";
        }
        if (uri.getUserInfo() != null) {
            return "the address must not carry user information: '" + url + "'";
        }
        if (uri.getQuery() != null) {
            return "the address must not have a query string: '" + url + "'";
        }
        if (uri.getFragment() != null) {
            return "the address must not have a fragment: '" + url + "'";
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty()) {
            return path.equals("/")
                    ? "the address must not end in a trailing slash: '" + url + "'"
                    : "the address must have no path, only host and optional port"
                            + " (got '" + url + "')";
        }
        return null;
    }

    /** Whether {@code raw} is a usable coordination-server address. */
    public static boolean isValid(String raw, boolean allowPlainHttp) {
        return problem(raw, allowPlainHttp) == null;
    }

    /**
     * Whether {@code url} points at this machine. A loopback address is a
     * perfectly good development target and a useless one to bake into an
     * installer somebody else runs, so the two callers want opposite answers
     * and both ask here.
     */
    public static boolean isLoopback(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            return ProductionSafetyValidator.isLoopbackHost(new URI(url.trim()).getHost());
        } catch (Exception e) {
            return false;
        }
    }
}
