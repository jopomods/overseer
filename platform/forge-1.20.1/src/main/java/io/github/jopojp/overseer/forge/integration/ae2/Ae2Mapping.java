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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

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

    static final MetricDescriptor JOB_PROGRESS = new MetricDescriptor("job_progress", MetricKind.GAUGE, "", 0.01);
    static final MetricDescriptor JOB_TOTAL = new MetricDescriptor("job_total", MetricKind.GAUGE, "", 0.0);
    static final MetricDescriptor JOB_ELAPSED = new MetricDescriptor("job_elapsed", MetricKind.GAUGE, "s", 0.05);
    static final MetricDescriptor BYTES_USED = new MetricDescriptor("bytes_used", MetricKind.GAUGE, "B", 0.001);
    static final MetricDescriptor BYTES_TOTAL = new MetricDescriptor("bytes_total", MetricKind.GAUGE, "B", 0.0);
    static final MetricDescriptor TYPES_USED = new MetricDescriptor("types_used", MetricKind.GAUGE, "", 0.0);
    static final MetricDescriptor TYPES_TOTAL = new MetricDescriptor("types_total", MetricKind.GAUGE, "", 0.0);
    /** Per drive, and summed per grid: storage cells (AE2 basic cells; add-on cells only by state). */
    static final List<MetricDescriptor> CELL_METRICS = List.of(BYTES_USED, BYTES_TOTAL, TYPES_USED, TYPES_TOTAL);

    /** Per crafting CPU, only while it has a job. */
    static final List<MetricDescriptor> CPU_METRICS = List.of(JOB_PROGRESS, JOB_TOTAL, JOB_ELAPSED);

    static final List<MetricDescriptor> METRICS = List.of(POWER_STORED, POWER_MAX, POWER_USAGE, POWER_INJECTION,
            ITEM_TYPES, ITEMS, FLUID_TYPES, FLUIDS, CHANNELS, CPUS_BUSY);

    /** Grid and CPU metrics: what the provider declares. */
    static final List<MetricDescriptor> ALL_METRICS = java.util.stream.Stream.of(METRICS, CPU_METRICS, CELL_METRICS)
            .flatMap(List::stream).toList();

    private Ae2Mapping() {}

    public static ResourceRef grid(String id) {
        return new ResourceRef(PROVIDER, "grid", id);
    }

    public static ResourceRef cpu(String id) {
        return new ResourceRef(PROVIDER, "cpu", id);
    }

    public static ResourceRef drive(String id) {
        return new ResourceRef(PROVIDER, "drive", id);
    }

    public static ProviderSnapshot snapshot(Timestamp at, List<Grid> grids) {
        Map<ResourceRef, Map<String, String>> resources = new HashMap<>();
        List<Sample> samples = new ArrayList<>();
        Map<ResourceRef, List<io.github.jopojp.overseer.core.provider.Stack>> inventories = new HashMap<>();
        Map<ResourceRef, List<io.github.jopojp.overseer.core.provider.JobLine>> jobs = new HashMap<>();
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
            attrs.put("kind", g.kind().name().toLowerCase(Locale.ROOT));
            resources.put(ref, attrs);
            // Hidden grids change their id whenever the grid changes: no history for them.
            if (g.kind() == Ae2Model.Kind.HIDDEN) continue;

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
                if (g.inventory() != null) inventories.put(ref, g.inventory());
            }
            samples.add(new Sample(ref, CHANNELS.key(), g.channels()));
            samples.add(new Sample(ref, CPUS_BUSY.key(), g.crafting().busy()));
            if (!g.drives().isEmpty()) {
                // Cells keep their contents while unpowered; only booting grids hide them.
                if (!g.booting()) {
                    samples.add(new Sample(ref, BYTES_USED.key(), g.drives().stream().mapToLong(Ae2Model.Drive::usedBytes).sum()));
                    samples.add(new Sample(ref, BYTES_TOTAL.key(), g.drives().stream().mapToLong(Ae2Model.Drive::totalBytes).sum()));
                    samples.add(new Sample(ref, TYPES_USED.key(), g.drives().stream().mapToLong(Ae2Model.Drive::usedTypes).sum()));
                    samples.add(new Sample(ref, TYPES_TOTAL.key(), g.drives().stream().mapToLong(Ae2Model.Drive::totalTypes).sum()));
                }
                for (Ae2Model.Drive drive : g.drives()) drive(g, drive, resources, samples);
            }
            for (Ae2Model.Cpu cpu : g.crafting().list()) {
                cpu(g, cpu, resources, samples);
                if (cpu.job() != null) jobs.put(cpu(cpu.id()), cpu.job().lines());
            }
        }
        return new ProviderSnapshot(PROVIDER, at, resources, samples, inventories, jobs);
    }

    /**
     * Who may see and edit which grid (ADR-0011). The owner sees and edits; members of the
     * owner's team see. Dashboard admins are handled by the access service. Shares follow.
     */
    /**
     * A crafting CPU: a resource of its own so that jobs have a history (the {@code job}
     * attribute changes when a job starts or ends). Progress is a metric; it changes too often
     * for events.
     */
    private static void cpu(Grid g, Ae2Model.Cpu cpu, Map<ResourceRef, Map<String, String>> resources,
            List<Sample> samples) {
        ResourceRef ref = cpu(cpu.id());
        var attrs = new LinkedHashMap<String, String>();
        attrs.put("grid", g.id());
        attrs.put("name", cpu.name() == null ? "" : cpu.name());
        attrs.put("storage", Long.toString(cpu.storage()));
        attrs.put("coProcessors", Integer.toString(cpu.coProcessors()));
        attrs.put("mode", cpu.mode());
        Ae2Model.Job job = cpu.job();
        attrs.put("busy", Boolean.toString(job != null));
        attrs.put("job", job == null ? "" : job.key());
        attrs.put("jobKind", job == null ? "" : job.kind());
        attrs.put("jobName", job == null ? "" : job.name());
        attrs.put("jobAmount", job == null ? "" : Long.toString(job.amount()));
        resources.put(ref, attrs);
        if (job != null) {
            samples.add(new Sample(ref, JOB_PROGRESS.key(), job.progress()));
            samples.add(new Sample(ref, JOB_TOTAL.key(), job.totalItems()));
            samples.add(new Sample(ref, JOB_ELAPSED.key(), job.elapsedNanos() / 1e9));
        }
    }

    /**
     * A drive or ME chest. The cells and their state are one attribute: it changes when a cell is
     * inserted, removed or gets full, which makes those moments events. Bytes are metrics.
     */
    private static void drive(Grid g, Ae2Model.Drive drive, Map<ResourceRef, Map<String, String>> resources,
            List<Sample> samples) {
        ResourceRef ref = drive(drive.id());
        var attrs = new LinkedHashMap<String, String>();
        attrs.put("grid", g.id());
        attrs.put("block", drive.block());
        var cells = new StringBuilder();
        for (Ae2Model.Cell cell : drive.cells()) {
            if (!cells.isEmpty()) cells.append(';');
            cells.append(cell.slot()).append('=').append(cell.item()).append('=').append(cell.state());
        }
        attrs.put("cells", cells.toString());
        resources.put(ref, attrs);
        if (g.booting()) return;
        samples.add(new Sample(ref, BYTES_USED.key(), drive.usedBytes()));
        samples.add(new Sample(ref, BYTES_TOTAL.key(), drive.totalBytes()));
        samples.add(new Sample(ref, TYPES_USED.key(), drive.usedTypes()));
        samples.add(new Sample(ref, TYPES_TOTAL.key(), drive.totalTypes()));
    }

    public static final class AccessIndex {
        public static final AccessIndex EMPTY = new AccessIndex(Map.of(), Map.of(), Map.of());

        private final Map<String, UUID> owners;
        private final Map<String, Set<UUID>> teams;
        /** Resource key of a part (CPU, drive) to the id of its grid. */
        private final Map<String, String> partGrids;

        private AccessIndex(Map<String, UUID> owners, Map<String, Set<UUID>> teams, Map<String, String> partGrids) {
            this.owners = owners;
            this.teams = teams;
            this.partGrids = partGrids;
        }

        public static AccessIndex of(List<Grid> grids) {
            return of(grids, owner -> Set.of());
        }

        /** @param teammates the other team members of an owner */
        public static AccessIndex of(List<Grid> grids, Function<UUID, Set<UUID>> teammates) {
            var owners = new HashMap<String, UUID>();
            var teams = new HashMap<String, Set<UUID>>();
            var cache = new HashMap<UUID, Set<UUID>>();
            var partGrids = new HashMap<String, String>();
            for (Grid g : grids) {
                for (Ae2Model.Cpu cpu : g.crafting().list()) partGrids.put(cpu(cpu.id()).key(), g.id());
                for (Ae2Model.Drive drive : g.drives()) partGrids.put(drive(drive.id()).key(), g.id());
                if (g.owner() == null) continue;
                owners.put(g.id(), g.owner());
                Set<UUID> team = cache.computeIfAbsent(g.owner(), teammates);
                if (!team.isEmpty()) teams.put(g.id(), Set.copyOf(team));
            }
            return new AccessIndex(Map.copyOf(owners), Map.copyOf(teams), Map.copyOf(partGrids));
        }

        /** The grid of a part (crafting CPU, drive): parts are visible exactly like their grid. */
        public ResourceRef parent(ResourceRef ref) {
            if (!ref.provider().equals(PROVIDER) || ref.type().equals("grid")) return null;
            String grid = partGrids.get(ref.key());
            return grid == null ? null : grid(grid);
        }

        public boolean canView(UUID player, ResourceRef ref) {
            String grid = gridId(player, ref);
            return grid != null && (player.equals(owners.get(grid)) || teams.getOrDefault(grid, Set.of()).contains(player));
        }

        /** Owners may name their grid. */
        public boolean canEdit(UUID player, ResourceRef ref) {
            String grid = gridId(player, ref);
            return grid != null && player.equals(owners.get(grid));
        }

        private static String gridId(UUID player, ResourceRef ref) {
            if (player == null || !ref.provider().equals(PROVIDER) || !ref.type().equals("grid")) return null;
            return ref.id();
        }
    }
}
