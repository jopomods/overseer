package io.github.jopojp.overseer.forge.integration.ae2;

import io.github.jopojp.overseer.core.provider.Stack;
import java.util.List;
import java.util.UUID;

/**
 * Plain copies of Applied Energistics 2 grid state, read on the server thread by
 * {@link Ae2Reader}. Free of AE2 and Minecraft types so mapping and access rules can be
 * unit-tested.
 */
public final class Ae2Model {
    private Ae2Model() {}

    /**
     * How a grid is identified (ADR-0011): by its controller, by a position a player registered
     * ({@code /ov ae2 register}) or an admin adopted, or not at all (hidden: no controller, not
     * registered; id is its lowest node and changes with the grid, admins only).
     */
    public enum Kind { CONTROLLER, REGISTERED, HIDDEN }

    /** AE2 controller states, mirrored by name. */
    public enum ControllerState { NO_CONTROLLER, CONTROLLER_ONLINE, CONTROLLER_CONFLICT }

    /** Power in AE (AE2's own unit, 1 AE = 2 FE); rates in AE per tick. */
    public record Energy(double stored, double max, double usage, double injection, double idle, boolean powered) {}

    /**
     * Cached network inventory by key type. Fluid amounts in mB.
     *
     * @param otherTypes keys of other types (e.g. chemicals from add-ons)
     */
    public record Storage(int itemTypes, long items, int fluidTypes, long fluids, int otherTypes) {}

    /** @param list the CPUs one by one; empty when not read */
    public record Crafting(int cpus, int busy, long storage, int coProcessors, List<Cpu> list) {
        public Crafting {
            list = List.copyOf(list);
        }

        public Crafting(int cpus, int busy, long storage, int coProcessors) {
            this(cpus, busy, storage, coProcessors, List.of());
        }
    }

    /**
     * A drive or ME chest (anything AE2 calls {@code IChestOrDrive}, add-on drives included).
     *
     * @param id    {@code dimension@x,y,z} of the block
     * @param block    registry id of the block, e.g. {@code ae2:drive}
     * @param priority storage priority, {@code null} for drives without one (add-ons)
     */
    public record Drive(String id, String block, List<Cell> cells, Integer priority) {
        public Drive {
            cells = List.copyOf(cells);
        }

        public Drive(String id, String block, List<Cell> cells) {
            this(id, block, cells, null);
        }

        public long usedBytes() {
            return cells.stream().mapToLong(c -> Math.max(0, c.usedBytes())).sum();
        }

        public long totalBytes() {
            return cells.stream().mapToLong(c -> Math.max(0, c.totalBytes())).sum();
        }

        public long usedTypes() {
            return cells.stream().mapToLong(c -> Math.max(0, c.usedTypes())).sum();
        }

        public long totalTypes() {
            return cells.stream().mapToLong(c -> Math.max(0, c.totalTypes())).sum();
        }
    }

    /**
     * A storage cell in a drive slot. Byte and type counts are -1 for cells AE2 does not
     * describe that way (add-on cells).
     *
     * @param item  registry id of the cell item
     * @param state {@code empty}, {@code not_empty}, {@code types_full} or {@code full}
     */
    public record Cell(int slot, String item, String state, long usedBytes, long totalBytes, long usedTypes,
            long totalTypes) {}

    /**
     * One crafting CPU (ADR-0011).
     *
     * @param id   {@code dimension@x,y,z} of the lowest corner of the multiblock; stable while
     *             the CPU stands. CPUs that are no multiblock (add-ons) get
     *             {@code <grid id>#<index>}, which may change.
     * @param name custom name (renamed in an anvil), {@code null} if none
     * @param mode AE2 CPU selection mode, lower case
     * @param job    the running job, {@code null} when idle
     * @param number position in AE2's CPU list, as AE2 shows unnamed CPUs ("CPU #3"); 0 if unknown
     * @param suspended the job is suspended (AE2 builds that support it, ADR-0017)
     */
    public record Cpu(String id, String name, long storage, int coProcessors, String mode, Job job, int number,
            boolean suspended) {
        public Cpu(String id, String name, long storage, int coProcessors, String mode, Job job, int number) {
            this(id, name, storage, coProcessors, mode, job, number, false);
        }

        public Cpu(String id, String name, long storage, int coProcessors, String mode, Job job) {
            this(id, name, storage, coProcessors, mode, job, 0);
        }
    }

    /**
     * A pattern provider, block or part (anything implementing AE2's {@code PatternProviderLogicHost},
     * add-on providers included).
     *
     * @param id        {@code dimension@x,y,z} of the block, plus {@code /<side>} for a part on a cable
     * @param block     registry id of the block or part item, e.g. {@code ae2:pattern_provider}
     * @param name      custom name, {@code null} if none
     * @param group     the name AE2 shows in the Pattern Access Terminal (custom name, else the machine)
     * @param groupIcon stack key of the icon AE2 shows there; {@code null} if none
     * @param lock      AE2 lock crafting mode, lower case ({@code none}, {@code lock_until_pulse}, ...)
     * @param locked    why the provider is locked right now, {@code none} if it is not
     * @param icon      registry id of the item chosen as icon from the dashboard, {@code null} if none
     */
    public record Provider(String id, String block, String name, String group, String groupIcon, int priority,
            boolean blocking, String lock, String locked, boolean visible, int patterns, String icon) {}

    /**
     * One pattern of a provider, read with the inventory scan.
     *
     * @param provider id of the provider
     * @param output   primary output (amount per craft)
     * @param inputs   first possible input of each slot, amount per craft
     */
    public record Pattern(String provider, Stack output, List<Stack> inputs) {
        public Pattern {
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * A device that needs a channel and has none (AE2 shows it dark), read with the inventory scan.
     *
     * @param id   {@code dimension@x,y,z} of the block, plus {@code /<side>} for a part
     * @param item stack key of what AE2 shows for the device (its item)
     * @param name its name
     */
    public record Offline(String id, String item, String name) {}

    /**
     * A crafting job.
     *
     * @param key         stack key of the requested output (see {@code Stack})
     * @param amount      requested amount
     * @param totalItems  items the whole job crafts (AE2's count, all steps)
     * @param progress    items of those done
     * @param lines       what the job holds and waits for (AE2 status screen); empty if unknown
     */
    public record Job(String key, String kind, String name, long amount, long totalItems, long progress,
            long elapsedNanos, List<io.github.jopojp.overseer.core.provider.JobLine> lines) {
        public Job {
            lines = List.copyOf(lines);
        }

        public Job(String key, String kind, String name, long amount, long totalItems, long progress,
                long elapsedNanos) {
            this(key, kind, name, amount, totalItems, progress, elapsedNanos, List.of());
        }
    }

    /**
     * A grid with at least one controller, identified by its controller (ADR-0011).
     *
     * @param dimension  dimension of the identifying controller block
     * @param owner      player who placed the identifying controller, {@code null} if unknown
     * @param ownerName  last known name of the owner, may be {@code null}
     * @param nodes      number of grid nodes (cables, machines, parts)
     * @param channels   channels in use
     * @param inventory  full contents when scanned this time (every 10 s), otherwise {@code null}
     * @param drives     drives and ME chests
     * @param watched    current amount of each watched stack key that AE2 knows (every second)
     * @param placers    players who placed any node of the grid; only read when placer access is on
     * @param providers  pattern providers
     * @param patterns   their patterns when read this time (inventory scans), otherwise {@code null}
     * @param offline    devices without a channel when read this time (inventory scans), otherwise {@code null}
     */
    public record Grid(
            String dimension,
            int x,
            int y,
            int z,
            UUID owner,
            String ownerName,
            int nodes,
            int controllers,
            ControllerState state,
            boolean booting,
            int channels,
            Energy energy,
            Storage storage,
            Crafting crafting,
            Kind kind,
            List<Stack> inventory,
            List<Drive> drives,
            java.util.Map<String, Long> watched,
            java.util.Set<UUID> placers,
            List<Provider> providers,
            List<Pattern> patterns,
            List<Offline> offline) {

        public Grid {
            placers = placers == null ? java.util.Set.of() : java.util.Set.copyOf(placers);
            providers = providers == null ? List.of() : List.copyOf(providers);
            if (patterns != null) patterns = List.copyOf(patterns);
            if (offline != null) offline = List.copyOf(offline);
            if (inventory != null) inventory = List.copyOf(inventory);
            drives = drives == null ? List.of() : List.copyOf(drives);
            watched = watched == null ? java.util.Map.of() : java.util.Map.copyOf(watched);
        }

        public Grid(String dimension, int x, int y, int z, UUID owner, String ownerName, int nodes, int controllers,
                ControllerState state, boolean booting, int channels, Energy energy, Storage storage,
                Crafting crafting, Kind kind, List<Stack> inventory, List<Drive> drives,
                java.util.Map<String, Long> watched, java.util.Set<UUID> placers) {
            this(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy, storage,
                    crafting, kind, inventory, drives, watched, placers, List.of(), null, null);
        }

        /** The same grid with these patterns (cached between scans). */
        public Grid withPatterns(List<Pattern> list) {
            return new Grid(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy,
                    storage, crafting, kind, inventory, drives, watched, placers, providers, list, offline);
        }

        /** The same grid with these devices without channel (cached between scans). */
        public Grid withOffline(List<Offline> list) {
            return new Grid(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy,
                    storage, crafting, kind, inventory, drives, watched, placers, providers, patterns, list);
        }

        public Grid(String dimension, int x, int y, int z, UUID owner, String ownerName, int nodes, int controllers,
                ControllerState state, boolean booting, int channels, Energy energy, Storage storage,
                Crafting crafting, Kind kind, List<Stack> inventory, List<Drive> drives) {
            this(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy, storage,
                    crafting, kind, inventory, drives, java.util.Map.of(), java.util.Set.of());
        }

        public Grid(String dimension, int x, int y, int z, UUID owner, String ownerName, int nodes, int controllers,
                ControllerState state, boolean booting, int channels, Energy energy, Storage storage,
                Crafting crafting, Kind kind, List<Stack> inventory, List<Drive> drives,
                java.util.Map<String, Long> watched) {
            this(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy, storage,
                    crafting, kind, inventory, drives, watched, java.util.Set.of());
        }

        public Grid(String dimension, int x, int y, int z, UUID owner, String ownerName, int nodes, int controllers,
                ControllerState state, boolean booting, int channels, Energy energy, Storage storage,
                Crafting crafting, Kind kind, List<Stack> inventory) {
            this(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy, storage,
                    crafting, kind, inventory, List.of());
        }

        public Grid(String dimension, int x, int y, int z, UUID owner, String ownerName, int nodes, int controllers,
                ControllerState state, boolean booting, int channels, Energy energy, Storage storage,
                Crafting crafting, Kind kind) {
            this(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy, storage,
                    crafting, kind, null);
        }

        public Grid(String dimension, int x, int y, int z, UUID owner, String ownerName, int nodes, int controllers,
                ControllerState state, boolean booting, int channels, Energy energy, Storage storage,
                Crafting crafting) {
            this(dimension, x, y, z, owner, ownerName, nodes, controllers, state, booting, channels, energy, storage,
                    crafting, Kind.CONTROLLER);
        }

        /** Dimension and position of the identifying controller or registered block (ADR-0005, ADR-0011). */
        public String id() {
            return dimension + "@" + x + "," + y + "," + z;
        }
    }
}
