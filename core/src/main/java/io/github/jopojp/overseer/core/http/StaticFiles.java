package io.github.jopojp.overseer.core.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves the embedded web UI from the classpath (ADR-0009).
 *
 * <p>Only paths made of safe characters are looked up, and only inside the bundle root,
 * so there is no way to reach other resources. Unknown paths without a file extension
 * fall back to {@code index.html} (client-side routing).
 */
public final class StaticFiles {
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._\\-/]*");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"));
    private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final String root;
    private final ClassLoader loader;
    private final Map<String, Optional<byte[]>> cache = new ConcurrentHashMap<>();

    /** @param root classpath directory, e.g. {@code overseer-web} */
    public StaticFiles(String root, ClassLoader loader) {
        this.root = root.replaceAll("^/+|/+$", "");
        this.loader = loader;
    }

    public Optional<Response> serve(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (!SAFE_PATH.matcher(relative).matches() || relative.contains("..") || relative.contains("//")) {
            return Optional.empty();
        }
        if (relative.isEmpty() || relative.endsWith("/")) relative = relative + "index.html";

        Optional<byte[]> content = load(relative);
        String served = relative;
        if (content.isEmpty() && !lastSegment(relative).contains(".")) {
            served = "index.html";
            content = load(served);
        }
        if (content.isEmpty()) return Optional.empty();

        String ext = extension(served);
        Response response = Response.bytes(200, TYPES.getOrDefault(ext, "application/octet-stream"), content.get());
        // Vite emits content-hashed file names under assets/: safe to cache forever.
        response.header("Cache-Control", served.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache");
        if (ext.equals("html")) response.header("Content-Security-Policy", CSP);
        return Optional.of(response);
    }

    private Optional<byte[]> load(String relative) {
        return cache.computeIfAbsent(relative, r -> {
            try (InputStream in = loader.getResourceAsStream(root + "/" + r)) {
                return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String extension(String path) {
        String last = lastSegment(path);
        int dot = last.lastIndexOf('.');
        return dot >= 0 ? last.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
