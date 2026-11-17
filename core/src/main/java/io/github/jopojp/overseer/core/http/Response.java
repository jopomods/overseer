package io.github.jopojp.overseer.core.http;

import io.github.jopojp.overseer.core.auth.Principal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a handler returns. Either a complete response or, for {@link #sse}, the instruction
 * to keep the connection open as an event stream for the given principal.
 */
public final class Response {
    private final int status;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<String> cookies = new ArrayList<>();
    private final byte[] body;
    private final Principal streamFor;

    private Response(int status, String contentType, byte[] body, Principal streamFor) {
        this.status = status;
        this.body = body;
        this.streamFor = streamFor;
        if (contentType != null) headers.put("Content-Type", contentType);
    }

    public static Response json(int status, Object value) {
        return new Response(status, "application/json; charset=utf-8",
                Json.GSON.toJson(value).getBytes(StandardCharsets.UTF_8), null);
    }

    public static Response json(Object value) {
        return json(200, value);
    }

    public static Response noContent() {
        return new Response(204, null, new byte[0], null);
    }

    public static Response redirect(String location) {
        return new Response(302, null, new byte[0], null).header("Location", location);
    }

    public static Response bytes(int status, String contentType, byte[] body) {
        return new Response(status, contentType, body, null);
    }

    public static Response error(HttpError error) {
        return json(error.status(), Map.of("error", Map.of("code", error.code(), "message", error.getMessage())));
    }

    /** Keep the connection open as a Server-Sent Events stream for this principal. */
    public static Response sse(Principal principal) {
        return new Response(200, "text/event-stream; charset=utf-8", new byte[0], principal);
    }

    public Response header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** Adds a {@code Set-Cookie} header. */
    public Response cookie(String setCookie) {
        cookies.add(setCookie);
        return this;
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public List<String> cookies() {
        return cookies;
    }

    public byte[] body() {
        return body;
    }

    public boolean isStream() {
        return streamFor != null;
    }

    public Principal streamPrincipal() {
        return streamFor;
    }
}
