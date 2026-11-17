package io.github.jopojp.overseer.core.http;

import java.net.URI;
import java.util.Locale;

/**
 * Same-origin enforcement for state-changing requests (ADR-0007). Overseer sends no CORS
 * headers, so browsers block cross-origin reads; this blocks cross-origin writes.
 * Requests authenticated by bearer token are exempt (no ambient credentials involved).
 */
final class OriginCheck {
    private OriginCheck() {}

    static boolean needsCheck(String method, boolean hasBearer) {
        return !hasBearer && !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));
    }

    /** {@code true} if {@code origin} names the same host and port the user addressed. */
    static boolean sameOrigin(String origin, String host, boolean secure) {
        if (origin == null || host == null || origin.equals("null")) return false;
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null) return false;
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            int originPort = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
            String originHost = uri.getHost().toLowerCase(Locale.ROOT);

            String h = host.trim().toLowerCase(Locale.ROOT);
            String hostName;
            int hostPort;
            if (h.startsWith("[")) {
                int end = h.indexOf(']');
                hostName = h.substring(1, end);
                hostPort = end + 2 <= h.length() && h.charAt(end + 1) == ':'
                        ? Integer.parseInt(h.substring(end + 2)) : defaultPort(scheme, secure);
            } else {
                int colon = h.lastIndexOf(':');
                hostName = colon >= 0 ? h.substring(0, colon) : h;
                hostPort = colon >= 0 ? Integer.parseInt(h.substring(colon + 1)) : defaultPort(scheme, secure);
            }
            String bareOriginHost = originHost.startsWith("[") ? originHost.substring(1, originHost.length() - 1) : originHost;
            return bareOriginHost.equals(hostName) && originPort == hostPort;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int defaultPort(String originScheme, boolean secure) {
        return originScheme.equals("https") || secure ? 443 : 80;
    }
}
