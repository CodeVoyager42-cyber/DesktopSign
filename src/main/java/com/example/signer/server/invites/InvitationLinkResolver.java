package com.example.signer.server.invites;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Decides the base URL that emailed invitation links are built from.
 *
 * <p>An emailed link is only useful if the SIGNER can open it, so a
 * {@code localhost} base is useless the moment the signer sits at another
 * machine. Resolution order, first match wins:
 *
 * <ol>
 *   <li>{@code signature.remote-server.invitation.public-base-url}
 *       ({@code SIGNER_PUBLIC_BASE_URL}) — an explicit public host or
 *       tunnel; always honoured verbatim.</li>
 *   <li>{@code app.base-url} ({@code APP_BASE_URL}) when it is not a
 *       loopback address.</li>
 *   <li>An address of this machine discovered at startup: a real LAN IPv4,
 *       skipping virtual adapters (WSL/Hyper-V/Docker/VMware/VPN) and
 *       link-local addresses. The scheme, port and any path prefix of
 *       {@code app.base-url} are preserved.</li>
 *   <li>{@code app.base-url} as a last resort. Links then only open on this
 *       machine, which is logged as a warning rather than passed off as a
 *       working invitation.</li>
 * </ol>
 *
 * <p>Nothing here reaches the network: detection is local interface
 * enumeration. The decision is logged once at startup and exposed through
 * {@code GET /api/server-info/invitation-link} so the sender can see the
 * address recipients will actually be sent.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class InvitationLinkResolver {

    private static final Logger log =
            LoggerFactory.getLogger(InvitationLinkResolver.class);

    /**
     * Substrings that identify an adapter which is not a real path to this
     * machine for somebody else: virtual switches, VM host-only networks,
     * container bridges and VPN/TAP devices. Matching is case-insensitive
     * against both the interface name and its display name.
     */
    private static final List<String> NON_LAN_HINTS = List.of(
            "vethernet", "hyper-v", "virtual", "vmware", "virtualbox",
            "docker", "wsl", "loopback", "bluetooth", "tap-", "tun",
            "npcap", "zerotier", "radmin", "vpn", "tailscale", "default switch");

    private final String configuredBaseUrl;
    private final String publicBaseUrl;
    private final int serverPort;

    private String resolved;
    private String source;
    private boolean localOnly;

    public InvitationLinkResolver(
            @Value("${app.base-url:http://localhost:3000}")
            String configuredBaseUrl,
            @Value("${signature.remote-server.invitation.public-base-url:}")
            String publicBaseUrl,
            @Value("${server.port:8080}") int serverPort) {
        this.configuredBaseUrl = trimSlashes(configuredBaseUrl);
        this.publicBaseUrl = trimSlashes(publicBaseUrl);
        this.serverPort = serverPort;
    }

    @PostConstruct
    void resolve() {
        if (!publicBaseUrl.isBlank()) {
            resolved = publicBaseUrl;
            source = "SIGNER_PUBLIC_BASE_URL";
            localOnly = isLoopbackBaseUrl(resolved);
        } else if (!isLoopbackBaseUrl(configuredBaseUrl)) {
            resolved = configuredBaseUrl;
            source = "APP_BASE_URL";
            localOnly = false;
        } else {
            String host = detectLanHostAddress();
            if (host != null) {
                resolved = withHost(configuredBaseUrl, host);
                source = "auto-detected LAN address";
                localOnly = false;
            } else {
                resolved = configuredBaseUrl;
                source = "APP_BASE_URL";
                localOnly = true;
            }
        }

        if (localOnly) {
            log.warn("Invitation links will use {} ({}). That address only"
                            + " resolves on this machine, so a signer on"
                            + " another computer cannot open the emailed link."
                            + " Set SIGNER_PUBLIC_BASE_URL to a reachable"
                            + " address to fix it.", resolved, source);
        } else {
            log.info("Invitation links will use {} ({}).", resolved, source);
        }
    }

    /** Base URL for emailed invitation links, never with a trailing slash. */
    public String baseUrl() {
        return resolved == null ? configuredBaseUrl : resolved;
    }

    /** True when the base URL is a loopback address — unreachable remotely. */
    public boolean localOnly() {
        return localOnly;
    }

    /** Human-readable reason for the chosen base URL (for diagnostics). */
    public String source() {
        return source;
    }

    /**
     * The address discovered for this machine. A seam so the "no usable
     * address" branch stays testable on a machine that has one.
     */
    String detectLanHostAddress() {
        return detectLanHost();
    }

    /**
     * First usable non-loopback IPv4 address of a physical adapter, or
     * {@code null} when the machine has none (offline laptop, VPN-only).
     */
    static String detectLanHost() {
        List<Inet4Address> candidates = new ArrayList<>();
        try {
            for (NetworkInterface nic :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint()
                        || nic.isVirtual() || isNonLanAdapter(nic)) {
                    continue;
                }
                for (InetAddress address :
                        Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address v4
                            && !v4.isLoopbackAddress()
                            && !v4.isLinkLocalAddress()
                            && !v4.isAnyLocalAddress()) {
                        candidates.add(v4);
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("Could not enumerate network interfaces", e);
            return null;
        }
        candidates.sort(Comparator.comparingInt(InvitationLinkResolver::rank));
        return candidates.isEmpty() ? null
                : candidates.get(0).getHostAddress();
    }

    /**
     * House LANs use {@code 192.168/16} overwhelmingly; prefer it over
     * {@code 10/8} and {@code 172.16/12}, which are more often corporate or
     * container ranges, and put anything else last.
     */
    private static int rank(Inet4Address address) {
        byte[] octets = address.getAddress();
        int first = octets[0] & 0xff;
        int second = octets[1] & 0xff;
        if (first == 192 && second == 168) {
            return 0;
        }
        if (first == 10) {
            return 1;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return 2;
        }
        return 3;
    }

    private static boolean isNonLanAdapter(NetworkInterface nic) {
        String name = (String.valueOf(nic.getName()) + " "
                + String.valueOf(nic.getDisplayName())).toLowerCase(Locale.ROOT);
        return NON_LAN_HINTS.stream().anyMatch(name::contains);
    }

    /** True when the URL points at this machine only (localhost/127.0.0.0/8/::1). */
    static boolean isLoopbackBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return true;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || "::1".equals(lower)
                || "[::1]".equals(lower)) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(lower)) {
                if (!address.isLoopbackAddress()) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            // A hostname that does not resolve locally is probably a real
            // external host; do not second-guess the operator.
            return false;
        }
    }

    /** Same scheme, port and path as {@code baseUrl}, but at {@code host}. */
    private String withHost(String baseUrl, String host) {
        URI uri = URI.create(baseUrl);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String portPart;
        if (uri.getPort() != -1) {
            portPart = ":" + uri.getPort();
        } else if (serverPort == defaultPort(scheme)) {
            portPart = "";
        } else {
            portPart = ":" + serverPort;
        }
        return scheme + "://" + host + portPart + trimSlashes(path);
    }

    private static int defaultPort(String scheme) {
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    private static String trimSlashes(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
