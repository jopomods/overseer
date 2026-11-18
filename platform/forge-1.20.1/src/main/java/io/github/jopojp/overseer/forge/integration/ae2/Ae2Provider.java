package io.github.jopojp.overseer.forge.integration.ae2;

import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import net.minecraft.server.MinecraftServer;

/**
 * Applied Energistics 2 integration (ADR-0011): grids with a controller, their power,
 * inventory totals and crafting CPUs. Collects every second; the access index is rebuilt on
 * each collection.
 */
public final class Ae2Provider implements DataProvider {
    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(Ae2Mapping.PROVIDER,
            "Applied Energistics 2", Set.of(Capability.METRICS, Capability.EVENTS, Capability.ACCESS_CONTROL,
                    Capability.SHARING),
            Ae2Mapping.METRICS, 20, TimeUnit.MILLISECONDS.toNanos(2));

    private final MinecraftServer server;
    private final Teams teams;
    private volatile Ae2Mapping.AccessIndex access = Ae2Mapping.AccessIndex.EMPTY;

    public Ae2Provider(MinecraftServer server, Teams teams) {
        this.server = server;
        this.teams = teams;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        List<Ae2Model.Grid> grids = Ae2Reader.readAll(server);
        access = Ae2Mapping.AccessIndex.of(grids, teams::teammates);
        return Ae2Mapping.snapshot(at, grids);
    }

    @Override
    public AccessPolicy access() {
        return new AccessPolicy() {
            @Override
            public boolean canView(UUID player, ResourceRef resource) {
                return access.canView(player, resource);
            }

            @Override
            public boolean canEdit(UUID player, ResourceRef resource) {
                return access.canEdit(player, resource);
            }
        };
    }
}
