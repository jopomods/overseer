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
     * One crafting CPU (ADR-0011).
     *
     * @param id   {@code dimension@x,y,z} of the lowest corner of the multiblock; stable while
     *             the CPU stands. CPUs that are no multiblock (add-ons) get
     *             {@code <grid id>#<index>}, which may change.
     * @param name custom name (renamed in an anvil), {@code null} if none
     * @param mode AE2 CPU selection mode, lower case
     * @param job  the running job, {@code null} when idle
     */
    public record Cpu(String id, String name, long storage, int coProcessors, String mode, Job job) {}

    /**
     * A crafting job.
     *
     * @param key         stack key of the requested output (see {@code Stack})
     * @param amount      requested amount
     * @param totalItems  items the whole job crafts (AE2's count, all steps)
     * @param progress    items of those done
     */
    public record Job(String key, String kind, String name, long amount, long totalItems, long progress,
            long elapsedNanos) {}

    /**
     * A grid with at least one controller, identified by its controller (ADR-0011).
     *
     * @param dimension  dimension of the identifying controller block
     * @param owner      player who placed the identifying controller, {@code null} if unknown
     * @param ownerName  last known name of the owner, may be {@code null}
     * @param nodes      number of grid nodes (cables, machines, parts)
     * @param channels   channels in use
     * @param inventory  full contents when scanned this time (every 10 s), otherwise {@code null}
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
            List<Stack> inventory) {

        public Grid {
            if (inventory != null) inventory = List.copyOf(inventory);
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
