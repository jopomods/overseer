package io.github.jopojp.overseer.core.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pending in-game logins (ADR-0007). In memory only: codes live five minutes.
 *
 * <p>A browser creates a code and receives a poll secret (kept in an HttpOnly cookie).
 * The player confirms the code in game. Only the holder of the poll secret can then
 * collect the login, so seeing someone's code on screen is not enough to hijack it.
 *
 * <p>Thread-safe.
 */
public final class LinkCodes {
    public static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_PENDING = 1_000;

    public enum ConfirmResult { CONFIRMED, UNKNOWN_OR_EXPIRED, ALREADY_CONFIRMED }

    public enum State { PENDING, CONFIRMED, EXPIRED }

    /** A freshly created code; {@code pollSecret} goes into the browser's cookie. */
    public record Created(String code, String pollSecret, Instant expiresAt) {}

    /** Result of polling. {@code player} is set when {@code state} is {@code CONFIRMED}. */
    public record Status(State state, Optional<PlayerRef> player) {}

    private static final class Pending {
        final String normalizedCode;
        final Instant expiresAt;
        PlayerRef confirmedBy;

        Pending(String normalizedCode, Instant expiresAt) {
            this.normalizedCode = normalizedCode;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Pending> byCode = new HashMap<>();
    private final Map<String, Pending> bySecretHash = new HashMap<>();

    public synchronized Created create(Instant now) {
        purge(now);
        if (bySecretHash.size() >= MAX_PENDING) throw new IllegalStateException("too many pending logins");
        String code;
        do {
            code = Secrets.newLinkCode();
        } while (byCode.containsKey(Secrets.normalizeLinkCode(code)));
        String secret = Secrets.newToken();
        var pending = new Pending(Secrets.normalizeLinkCode(code), now.plus(TTL));
        byCode.put(pending.normalizedCode, pending);
        bySecretHash.put(Secrets.sha256(secret), pending);
        return new Created(code, secret, pending.expiresAt);
    }

    /** Called by {@code /ov link <code>}. Codes are case- and separator-insensitive. */
    public synchronized ConfirmResult confirm(String code, PlayerRef player, Instant now) {
        purge(now);
        Pending pending = byCode.get(Secrets.normalizeLinkCode(code));
        if (pending == null) return ConfirmResult.UNKNOWN_OR_EXPIRED;
        if (pending.confirmedBy != null) return ConfirmResult.ALREADY_CONFIRMED;
        pending.confirmedBy = player;
        return ConfirmResult.CONFIRMED;
    }

    /** Polled by the browser. A confirmed login is handed out exactly once. */
    public synchronized Status poll(String pollSecret, Instant now) {
        purge(now);
        if (pollSecret == null) return new Status(State.EXPIRED, Optional.empty());
        String hash = Secrets.sha256(pollSecret);
        Pending pending = bySecretHash.get(hash);
        if (pending == null) return new Status(State.EXPIRED, Optional.empty());
        if (pending.confirmedBy == null) return new Status(State.PENDING, Optional.empty());
        bySecretHash.remove(hash);
        byCode.remove(pending.normalizedCode);
        return new Status(State.CONFIRMED, Optional.of(pending.confirmedBy));
    }

    private void purge(Instant now) {
        bySecretHash.values().removeIf(p -> !now.isBefore(p.expiresAt));
        byCode.values().removeIf(p -> !now.isBefore(p.expiresAt));
    }
}
