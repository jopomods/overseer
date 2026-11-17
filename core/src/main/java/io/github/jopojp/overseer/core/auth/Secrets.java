package io.github.jopojp.overseer.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Random tokens, link codes and hashing (ADR-0007). */
public final class Secrets {
    /** No 0/O, 1/I/L: codes are typed by hand in game. */
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    /** 256-bit random token, URL-safe Base64 without padding. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Six characters shown as {@code ABC-123}. */
    public static String newLinkCode() {
        var code = new StringBuilder(CODE_LENGTH + 1);
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i == CODE_LENGTH / 2) code.append('-');
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Canonical form for lookups: upper case, separators and spaces removed. */
    public static String normalizeLinkCode(String input) {
        return input == null ? "" : input.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** SHA-256 hex digest; the only form in which secrets are stored or kept in maps. */
    public static String sha256(String secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
