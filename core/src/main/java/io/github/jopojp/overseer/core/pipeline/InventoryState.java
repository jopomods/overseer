package io.github.jopojp.overseer.core.pipeline;

import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Stack;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Newest inventory scan per resource (ADR-0011). Most snapshots carry no scan, so the newest
 * snapshot alone is not enough; this keeps each resource's last scan until the resource
 * disappears. Listens on {@link LatestState}; read by any thread.
 */
public final class InventoryState implements Consumer<ProviderSnapshot> {

    /** One full scan. {@code stacks} is immutable. */
    public record Scan(Timestamp at, List<Stack> stacks) {}

    private final ConcurrentHashMap<ResourceRef, Scan> scans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Jobs> jobs = new ConcurrentHashMap<>();

    /** The running jobs of one provider at one time. */
    public record Jobs(Timestamp at, java.util.Map<ResourceRef, List<io.github.jopojp.overseer.core.provider.JobLine>> lines) {}

    @Override
    public void accept(ProviderSnapshot snapshot) {
        jobs.put(snapshot.provider(), new Jobs(snapshot.at(), snapshot.jobs()));
        snapshot.inventories().forEach((ref, stacks) -> scans.put(ref, new Scan(snapshot.at(), List.copyOf(stacks))));
        scans.keySet().removeIf(ref -> ref.provider().equals(snapshot.provider())
                && !snapshot.resources().containsKey(ref));
    }

    public Optional<Scan> get(ResourceRef ref) {
        return Optional.ofNullable(scans.get(ref));
    }

    /** Lines of the running job of a resource; empty when it has none. */
    public Optional<Jobs> job(ResourceRef ref) {
        Jobs all = jobs.get(ref.provider());
        if (all == null || !all.lines().containsKey(ref)) return Optional.empty();
        return Optional.of(new Jobs(all.at(), java.util.Map.of(ref, all.lines().get(ref))));
    }
}
