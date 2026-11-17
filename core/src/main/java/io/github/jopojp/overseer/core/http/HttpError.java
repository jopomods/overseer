package io.github.jopojp.overseer.core.http;

/** An expected failure that maps to an HTTP status and a JSON error body. */
public final class HttpError extends RuntimeException {
    private final int status;
    private final String code;

    public HttpError(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static HttpError badRequest(String message) {
        return new HttpError(400, "bad_request", message);
    }

    public static HttpError unauthorized() {
        return new HttpError(401, "unauthorized", "Not logged in or the credential is no longer valid.");
    }

    public static HttpError forbidden(String message) {
        return new HttpError(403, "forbidden", message);
    }

    public static HttpError notFound() {
        return new HttpError(404, "not_found", "Not found.");
    }

    public static HttpError rateLimited() {
        return new HttpError(429, "rate_limited", "Too many requests. Try again in a minute.");
    }
}
