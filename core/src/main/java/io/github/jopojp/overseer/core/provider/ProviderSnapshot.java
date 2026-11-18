package io.github.jopojp.overseer.core.provider;

import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of one collection run (ADR-0004). Produced on the server thread,
 * then handed to the storage and API workers through a bounded queue. Never mutated
 * after construction, so any thread may read it.
 *
 * @param provider  provider id
 * @param at        when the data was read
 * @param resources current state of every observed resource: ref to attributes (name, owner, ...)
 * @param samples   metric values
 * @param inventories full inventory scans of some resources (capability INVENTORY). A resource
 *                    missing here was not scanned this time; it does not mean empty. Never
 *                    pushed to clients as part of the snapshot.
 */
public record ProviderSnapshot(
        String provider,
        Timestamp at,
        Map<ResourceRef, Map<String, String>> resources,
        List<Sample> samples,
        Map<ResourceRef, List<Stack>> inventories) {
    public ProviderSnapshot(String provider, Timestamp at, Map<ResourceRef, Map<String, String>> resources,
            List<Sample> samples) {
        this(provider, at, resources, samples, Map.of());
    }

    public ProviderSnapshot {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(at, "at");
        resources = Map.copyOf(resources);
        samples = List.copyOf(samples);
        inventories = Map.copyOf(inventories);
    }
}
