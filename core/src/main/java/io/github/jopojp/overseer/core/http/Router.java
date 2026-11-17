package io.github.jopojp.overseer.core.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal router: exact segments and {@code {param}} placeholders, e.g.
 * {@code /api/v1/me/credentials/{id}}. First match wins; a path that matches with another
 * method yields 405.
 */
public final class Router {
    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    private record Route(String method, String[] segments, Handler handler) {}

    /** A resolved route: the handler and the request with path parameters filled in. */
    public record Match(Handler handler, Request request) {}

    private final List<Route> routes = new ArrayList<>();

    public Router get(String pattern, Handler handler) {
        return add("GET", pattern, handler);
    }

    public Router post(String pattern, Handler handler) {
        return add("POST", pattern, handler);
    }

    public Router put(String pattern, Handler handler) {
        return add("PUT", pattern, handler);
    }

    public Router delete(String pattern, Handler handler) {
        return add("DELETE", pattern, handler);
    }

    public Router add(String method, String pattern, Handler handler) {
        routes.add(new Route(method, split(pattern), handler));
        return this;
    }

    /**
     * @return the match, or empty if no route has this path
     * @throws HttpError 405 if the path exists for another method
     */
    public Optional<Match> match(Request request) {
        String[] segments = split(request.path());
        boolean pathMatched = false;
        for (Route route : routes) {
            Map<String, String> params = bind(route.segments(), segments);
            if (params == null) continue;
            pathMatched = true;
            String method = request.method().equals("HEAD") ? "GET" : request.method();
            if (route.method().equals(method)) {
                return Optional.of(new Match(route.handler(), request.withPathParams(params)));
            }
        }
        if (pathMatched) throw new HttpError(405, "method_not_allowed", "Method not allowed.");
        return Optional.empty();
    }

    private static Map<String, String> bind(String[] pattern, String[] path) {
        if (pattern.length != path.length) return null;
        var params = new HashMap<String, String>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                if (path[i].isEmpty()) return null;
                params.put(p.substring(1, p.length() - 1), path[i]);
            } else if (!p.equals(path[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/", -1);
    }
}
