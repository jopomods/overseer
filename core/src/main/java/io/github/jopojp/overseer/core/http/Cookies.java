package io.github.jopojp.overseer.core.http;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Cookie parsing and {@code Set-Cookie} formatting with safe defaults (ADR-0007). */
public final class Cookies {
    public static final String SESSION = "ov_session";
    public static final String LINK = "ov_link";

    private Cookies() {}

    public static Map<String, String> parse(String header) {
        var cookies = new HashMap<String, String>();
        if (header == null) return cookies;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            cookies.putIfAbsent(name, value);
        }
        return cookies;
    }

    /** HttpOnly, SameSite=Strict; Secure when the request came over HTTPS. */
    public static String set(String name, String value, String path, Duration maxAge, boolean secure) {
        return name + "=" + value + "; Path=" + path + "; Max-Age=" + maxAge.toSeconds()
                + "; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
    }

    public static String clear(String name, String path, boolean secure) {
        return set(name, "", path, Duration.ZERO, secure);
    }
}
