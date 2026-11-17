package io.github.jopojp.overseer.core.pipeline;

import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Newest snapshot per provider, for live views and SSE (ADR-0004).
 * Written by the server thread, read by any thread.
 */
public final class LatestState {
    private final ConcurrentHashMap<String, ProviderSnapshot> latest = new ConcurrentHashMap<>();

    public void put(ProviderSnapshot snapshot) {
        latest.put(snapshot.provider(), snapshot);
    }

    public Optional<ProviderSnapshot> get(String provider) {
        return Optional.ofNullable(latest.get(provider));
    }

    /** Immutable copy of all current snapshots, keyed by provider id. */
    public Map<String, ProviderSnapshot> all() {
        return Map.copyOf(latest);
    }
}
