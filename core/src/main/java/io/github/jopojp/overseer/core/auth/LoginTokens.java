package io.github.jopojp.overseer.core.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One-time login links issued from the server console ({@code ov login <player>}),
 * the recovery path when a player cannot use {@code /ov link} (ADR-0007).
 * In memory only; valid for ten minutes. Thread-safe.
 */
public final class LoginTokens {
    public static final Duration TTL = Duration.ofMinutes(10);

    private record Issued(PlayerRef player, Instant expiresAt) {}

    private final Map<String, Issued> byHash = new HashMap<>();

    public synchronized String issue(PlayerRef player, Instant now) {
        purge(now);
        String token = Secrets.newToken();
        byHash.put(Secrets.sha256(token), new Issued(player, now.plus(TTL)));
        return token;
    }

    /** Redeems a token once. */
    public synchronized Optional<PlayerRef> redeem(String token, Instant now) {
        purge(now);
        if (token == null) return Optional.empty();
        Issued issued = byHash.remove(Secrets.sha256(token));
        return issued == null ? Optional.empty() : Optional.of(issued.player());
    }

    private void purge(Instant now) {
        byHash.values().removeIf(i -> !now.isBefore(i.expiresAt()));
    }
}
