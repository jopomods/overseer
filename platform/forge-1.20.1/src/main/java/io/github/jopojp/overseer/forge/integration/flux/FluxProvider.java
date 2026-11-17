package io.github.jopojp.overseer.forge.integration.flux;

import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Flux Networks integration (ADR-0008: access mirrors Flux membership).
 * Collects every second. The access index is rebuilt on each collection and read by HTTP
 * threads, so membership changes apply within one interval.
 */
public final class FluxProvider implements DataProvider {
    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(FluxMapping.PROVIDER, "Flux Networks",
            Set.of(Capability.METRICS, Capability.EVENTS, Capability.ACCESS_CONTROL),
            FluxMapping.METRICS, 20, TimeUnit.MILLISECONDS.toNanos(2));

    private volatile FluxMapping.AccessIndex access = FluxMapping.AccessIndex.EMPTY;

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        List<FluxModel.Network> networks = FluxReader.readAll();
        access = FluxMapping.AccessIndex.of(networks);
        return FluxMapping.snapshot(at, networks);
    }

    @Override
    public AccessPolicy access() {
        return new AccessPolicy() {
            @Override
            public boolean canView(java.util.UUID player, io.github.jopojp.overseer.core.provider.ResourceRef resource) {
                return access.canView(player, resource);
            }

            @Override
            public boolean canEdit(java.util.UUID player, io.github.jopojp.overseer.core.provider.ResourceRef resource) {
                return access.canEdit(player, resource);
            }
        };
    }
}
