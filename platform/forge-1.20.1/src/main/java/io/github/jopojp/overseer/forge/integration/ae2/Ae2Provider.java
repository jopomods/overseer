package io.github.jopojp.overseer.forge.integration.ae2;

import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * Applied Energistics 2 integration (ADR-0011): grids with a controller, their power,
 * inventory totals and crafting CPUs. Collects every second; the access index is rebuilt on
 * each collection.
 */
public final class Ae2Provider implements DataProvider {
    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(Ae2Mapping.PROVIDER,
            "Applied Energistics 2", Set.of(Capability.METRICS, Capability.EVENTS, Capability.INVENTORY, Capability.ACCESS_CONTROL,
                    Capability.SHARING),
            Ae2Mapping.ALL_METRICS, 20, TimeUnit.MILLISECONDS.toNanos(2));

    private final MinecraftServer server;
    private final Teams teams;
    private final Supplier<Map<String, UUID>> registrations;
    private final Supplier<Map<String, Set<String>>> watches;
    private java.util.function.BooleanSupplier placerAccess = () -> false;
    private volatile Ae2Mapping.AccessIndex access = Ae2Mapping.AccessIndex.EMPTY;
    private final Ae2Reader.StackCache stacks = new Ae2Reader.StackCache();
    /** Placers per grid id, refreshed with every inventory scan (10 s); walking all nodes costs. */
    private final Map<String, Set<UUID>> placers = new java.util.HashMap<>();
    private long collections;

    /**
     * @param registrations registered grids ({@code dimension@x,y,z} to owner), read every
     *                      collection; must be cheap
     */
    public Ae2Provider(MinecraftServer server, Teams teams, Supplier<Map<String, UUID>> registrations) {
        this(server, teams, registrations, Map::of);
    }

    /**
     * @param watches watched stack keys per grid id, read every collection; must be cheap
     */
    public Ae2Provider(MinecraftServer server, Teams teams, Supplier<Map<String, UUID>> registrations,
            Supplier<Map<String, Set<String>>> watches) {
        this.server = server;
        this.teams = teams;
        this.registrations = registrations;
        this.watches = watches;
    }

    /** Full inventory scan every 10th collection: every 10 s (ADR-0011). */
    static final int SCAN_EVERY = 10;

    /** Players who placed a node of a grid may view it (server setting {@code access.ae2PlacerAccess}). */
    public Ae2Provider placerAccess(java.util.function.BooleanSupplier enabled) {
        this.placerAccess = enabled;
        return this;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        boolean scan = collections++ % SCAN_EVERY == 0;
        List<Ae2Model.Grid> grids = Ae2Reader.readAll(server, registrations.get(), watches.get(), stacks, scan,
                placerAccess.getAsBoolean() ? placers : null);
        access = Ae2Mapping.AccessIndex.of(grids, teams::teammates, placerAccess.getAsBoolean());
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

            @Override
            public ResourceRef parent(ResourceRef resource) {
                return access.parent(resource);
            }
        };
    }
}
