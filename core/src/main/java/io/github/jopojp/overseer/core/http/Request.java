package io.github.jopojp.overseer.core.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An HTTP request as handlers see it. Immutable; detached from Netty buffers.
 *
 * @param method        upper-case method
 * @param path          decoded path without query
 * @param query         decoded query parameters
 * @param headers       header values keyed by lower-case name (first value)
 * @param cookies       cookie values by name
 * @param body          raw body
 * @param clientAddress resolved client IP (through trusted proxies)
 * @param secure        request reached the user over HTTPS
 * @param pathParams    values of {@code {name}} segments of the matched route
 */
public record Request(
        String method,
        String path,
        Map<String, List<String>> query,
        Map<String, String> headers,
        Map<String, String> cookies,
        byte[] body,
        String clientAddress,
        boolean secure,
        Map<String, String> pathParams) {

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    public Optional<String> queryParam(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) throw new IllegalStateException("no path parameter " + name);
        return value;
    }

    /** The token of an {@code Authorization: Bearer} header, if present. */
    public Optional<String> bearerToken() {
        return header("authorization")
                .filter(h -> h.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(h -> h.substring(7).trim());
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    Request withPathParams(Map<String, String> params) {
        return new Request(method, path, query, headers, cookies, body, clientAddress, secure, Map.copyOf(params));
    }
}
