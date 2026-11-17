package io.github.jopojp.overseer.core.pipeline;

import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Newest snapshot per provider, for live views and SSE (ADR-0004).
 * Written by the server thread, read by any thread.
 *
 * <p>Listeners are notified on the writing (server) thread and must only enqueue work.
 */
public final class LatestState {
    private static final Logger LOG = LoggerFactory.getLogger(LatestState.class);

    private final ConcurrentHashMap<String, ProviderSnapshot> latest = new ConcurrentHashMap<>();
    private final List<Consumer<ProviderSnapshot>> listeners = new CopyOnWriteArrayList<>();

    public void put(ProviderSnapshot snapshot) {
        latest.put(snapshot.provider(), snapshot);
        for (Consumer<ProviderSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException e) {
                LOG.warn("Overseer snapshot listener failed", e);
            }
        }
    }

    public Optional<ProviderSnapshot> get(String provider) {
        return Optional.ofNullable(latest.get(provider));
    }

    /** Immutable copy of all current snapshots, keyed by provider id. */
    public Map<String, ProviderSnapshot> all() {
        return Map.copyOf(latest);
    }

    public void addListener(Consumer<ProviderSnapshot> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<ProviderSnapshot> listener) {
        listeners.remove(listener);
    }
}
