package io.github.jopojp.overseer.forge.integration.ae2;

import io.github.jopojp.overseer.core.provider.MetricDescriptor;
import io.github.jopojp.overseer.core.provider.MetricKind;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Grid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns {@link Ae2Model} into Overseer resources and answers access questions from the last
 * collected state (ADR-0005, ADR-0008, ADR-0011). Pure: no AE2 or Minecraft classes.
 *
 * <p>Resources: {@code ae2/grid/<dimension>@x,y,z} with owner, state and counts as
 * attributes; power, inventory and crafting CPU metrics.
 */
public final class Ae2Mapping {
    public static final String PROVIDER = "ae2";

    static final MetricDescriptor POWER_STORED = new MetricDescriptor("power_stored", MetricKind.GAUGE, "AE", 0.01);
    static final MetricDescriptor POWER_MAX = new MetricDescriptor("power_max", MetricKind.GAUGE, "AE", 0.0);
    static final MetricDescriptor POWER_USAGE = new MetricDescriptor("power_usage", MetricKind.RATE, "AE/t", 0.02);
    static final MetricDescriptor POWER_INJECTION = new MetricDescriptor("power_injection", MetricKind.RATE, "AE/t", 0.02);
    static final MetricDescriptor ITEM_TYPES = new MetricDescriptor("item_types", MetricKind.GAUGE, "", 0.0);
    static final MetricDescriptor ITEMS = new MetricDescriptor("items", MetricKind.GAUGE, "", 0.001);
    static final MetricDescriptor FLUID_TYPES = new MetricDescriptor("fluid_types", MetricKind.GAUGE, "", 0.0);
    static final MetricDescriptor FLUIDS = new MetricDescriptor("fluids", MetricKind.GAUGE, "mB", 0.001);
    static final MetricDescriptor CHANNELS = new MetricDescriptor("channels", MetricKind.GAUGE, "", 0.0);
    static final MetricDescriptor CPUS_BUSY = new MetricDescriptor("cpus_busy", MetricKind.GAUGE, "", 0.0);

    static final List<MetricDescriptor> METRICS = List.of(POWER_STORED, POWER_MAX, POWER_USAGE, POWER_INJECTION,
            ITEM_TYPES, ITEMS, FLUID_TYPES, FLUIDS, CHANNELS, CPUS_BUSY);

    private Ae2Mapping() {}

    public static ResourceRef grid(String id) {
        return new ResourceRef(PROVIDER, "grid", id);
    }

    public static ProviderSnapshot snapshot(Timestamp at, List<Grid> grids) {
        Map<ResourceRef, Map<String, String>> resources = new HashMap<>();
        List<Sample> samples = new ArrayList<>();
        for (Grid g : grids) {
            ResourceRef ref = grid(g.id());
            var attrs = new LinkedHashMap<String, String>();
            attrs.put("dimension", g.dimension());
            attrs.put("pos", g.x() + "," + g.y() + "," + g.z());
            attrs.put("owner", g.owner() == null ? "" : g.owner().toString());
            attrs.put("ownerName", g.ownerName() == null ? "" : g.ownerName());
            attrs.put("state", g.state().name().toLowerCase(Locale.ROOT));
            attrs.put("booting", Boolean.toString(g.booting()));
            attrs.put("powered", Boolean.toString(g.energy().powered()));
            attrs.put("nodes", Integer.toString(g.nodes()));
            attrs.put("controllers", Integer.toString(g.controllers()));
            attrs.put("otherTypes", Integer.toString(g.storage().otherTypes()));
            attrs.put("cpus", Integer.toString(g.crafting().cpus()));
            attrs.put("cpuStorage", Long.toString(g.crafting().storage()));
            attrs.put("coProcessors", Integer.toString(g.crafting().coProcessors()));
            attrs.put("idlePower", Double.toString(g.energy().idle()));
            resources.put(ref, attrs);

            samples.add(new Sample(ref, POWER_STORED.key(), g.energy().stored()));
            samples.add(new Sample(ref, POWER_MAX.key(), g.energy().max()));
            samples.add(new Sample(ref, POWER_USAGE.key(), g.energy().usage()));
            samples.add(new Sample(ref, POWER_INJECTION.key(), g.energy().injection()));
            // A booting or unpowered grid reports an empty inventory. That is not real, so the
            // inventory metrics are left out and the last stored values hold (step function).
            if (!g.booting() && g.energy().powered()) {
                samples.add(new Sample(ref, ITEM_TYPES.key(), g.storage().itemTypes()));
                samples.add(new Sample(ref, ITEMS.key(), g.storage().items()));
                samples.add(new Sample(ref, FLUID_TYPES.key(), g.storage().fluidTypes()));
                samples.add(new Sample(ref, FLUIDS.key(), g.storage().fluids()));
            }
            samples.add(new Sample(ref, CHANNELS.key(), g.channels()));
            samples.add(new Sample(ref, CPUS_BUSY.key(), g.crafting().busy()));
        }
        return new ProviderSnapshot(PROVIDER, at, resources, samples);
    }

    /**
     * Who may see and edit which grid. For now only the owner (dashboard admins are handled
     * by the access service). FTB teams and shares follow (ADR-0011).
     */
    public static final class AccessIndex {
        public static final AccessIndex EMPTY = new AccessIndex(Map.of());

        private final Map<String, UUID> owners;

        private AccessIndex(Map<String, UUID> owners) {
            this.owners = owners;
        }

        public static AccessIndex of(List<Grid> grids) {
            var owners = new HashMap<String, UUID>();
            for (Grid g : grids) {
                if (g.owner() != null) owners.put(g.id(), g.owner());
            }
            return new AccessIndex(Map.copyOf(owners));
        }

        public boolean canView(UUID player, ResourceRef ref) {
            return isOwner(player, ref);
        }

        /** Owners may name their grid. */
        public boolean canEdit(UUID player, ResourceRef ref) {
            return isOwner(player, ref);
        }

        private boolean isOwner(UUID player, ResourceRef ref) {
            if (player == null || !ref.provider().equals(PROVIDER) || !ref.type().equals("grid")) return false;
            return player.equals(owners.get(ref.id()));
        }
    }
}
