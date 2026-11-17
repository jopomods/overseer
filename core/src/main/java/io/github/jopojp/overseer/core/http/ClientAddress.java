package io.github.jopojp.overseer.core.http;

import java.util.Set;

/**
 * Resolves who is really talking to us behind reverse proxies (ADR-0007).
 * {@code X-Forwarded-*} headers are honored only when the direct peer is a trusted proxy;
 * otherwise anyone could spoof their address or claim HTTPS.
 */
final class ClientAddress {
    private ClientAddress() {}

    /**
     * Walks {@code X-Forwarded-For} from the right, skipping trusted proxies, and returns the
     * first untrusted address. Falls back to the direct peer.
     */
    static String resolve(String peer, String forwardedFor, Set<String> trustedProxies) {
        if (forwardedFor == null || !trustedProxies.contains(peer)) return peer;
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) continue;
            if (!trustedProxies.contains(hop)) return hop;
        }
        return peer;
    }

    /** HTTPS as seen by the user: forced by config, or reported by a trusted proxy. */
    static boolean isSecure(String peer, String forwardedProto, Set<String> trustedProxies, boolean force) {
        if (force) return true;
        return forwardedProto != null && trustedProxies.contains(peer)
                && forwardedProto.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https");
    }

    /** The host the user addressed: {@code X-Forwarded-Host} from a trusted proxy, else {@code Host}. */
    static String host(String peer, String host, String forwardedHost, Set<String> trustedProxies) {
        if (forwardedHost != null && trustedProxies.contains(peer)) return forwardedHost.split(",")[0].trim();
        return host;
    }
}
