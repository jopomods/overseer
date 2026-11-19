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
 * @param jobs        lines of running jobs, e.g. per AE2 crafting CPU. Live only: never stored,
 *                    never pushed as part of the snapshot. A resource missing here has no job.
 * @param details     other live tables per resource (rows of named columns), e.g. the tick
 *                    profiler's top list. Live only, like {@code jobs}.
 * @param flows       what devices moved into or out of a storage network since the last collection,
 *                    per network (ADR-0020). Stored per minute; never pushed as part of the snapshot.
 */
public record ProviderSnapshot(
        String provider,
        Timestamp at,
        Map<ResourceRef, Map<String, String>> resources,
        List<Sample> samples,
        Map<ResourceRef, List<Stack>> inventories,
        Map<ResourceRef, List<JobLine>> jobs,
        Map<ResourceRef, List<Map<String, String>>> details,
        Map<ResourceRef, List<Flow>> flows) {
    public ProviderSnapshot(String provider, Timestamp at, Map<ResourceRef, Map<String, String>> resources,
            List<Sample> samples) {
        this(provider, at, resources, samples, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public ProviderSnapshot(String provider, Timestamp at, Map<ResourceRef, Map<String, String>> resources,
            List<Sample> samples, Map<ResourceRef, List<Stack>> inventories, Map<ResourceRef, List<JobLine>> jobs,
            Map<ResourceRef, List<Map<String, String>>> details) {
        this(provider, at, resources, samples, inventories, jobs, details, Map.of());
    }

    public ProviderSnapshot(String provider, Timestamp at, Map<ResourceRef, Map<String, String>> resources,
            List<Sample> samples, Map<ResourceRef, List<Stack>> inventories) {
        this(provider, at, resources, samples, inventories, Map.of(), Map.of(), Map.of());
    }

    public ProviderSnapshot(String provider, Timestamp at, Map<ResourceRef, Map<String, String>> resources,
            List<Sample> samples, Map<ResourceRef, List<Stack>> inventories, Map<ResourceRef, List<JobLine>> jobs) {
        this(provider, at, resources, samples, inventories, jobs, Map.of(), Map.of());
    }

    public ProviderSnapshot {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(at, "at");
        resources = Map.copyOf(resources);
        samples = List.copyOf(samples);
        inventories = Map.copyOf(inventories);
        jobs = Map.copyOf(jobs);
        details = Map.copyOf(details);
        flows = Map.copyOf(flows);
    }
}
