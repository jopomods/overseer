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
 */
public record ProviderSnapshot(
        String provider,
        Timestamp at,
        Map<ResourceRef, Map<String, String>> resources,
        List<Sample> samples) {
    public ProviderSnapshot {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(at, "at");
        resources = Map.copyOf(resources);
        samples = List.copyOf(samples);
    }
}
