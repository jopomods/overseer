package io.github.jopojp.overseer.core.http;

import java.util.Objects;
import java.util.Set;

/**
 * Web server settings from {@code overseer-server.toml} (ADR-0007).
 *
 * @param bindAddress         interface to listen on; {@code 127.0.0.1} by default, so the
 *                            dashboard is only reachable through a local reverse proxy
 * @param port                TCP port ({@code 0} = any free port, for tests)
 * @param trustedProxies      exact IP addresses whose {@code X-Forwarded-*} headers are trusted
 * @param forceSecureCookies  always mark cookies {@code Secure} (when TLS terminates elsewhere
 *                            without {@code X-Forwarded-Proto})
 * @param maxRequestBytes     larger requests are rejected with 413
 */
public record HttpConfig(
        String bindAddress, int port, Set<String> trustedProxies, boolean forceSecureCookies, int maxRequestBytes) {
    public static final HttpConfig DEFAULTS = new HttpConfig("127.0.0.1", 8765, Set.of("127.0.0.1", "::1"), false, 64 * 1024);

    public HttpConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        trustedProxies = Set.copyOf(trustedProxies);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port " + port);
        if (maxRequestBytes < 1024) throw new IllegalArgumentException("maxRequestBytes must be >= 1024");
    }

    public HttpConfig withPort(int newPort) {
        return new HttpConfig(bindAddress, newPort, trustedProxies, forceSecureCookies, maxRequestBytes);
    }
}
