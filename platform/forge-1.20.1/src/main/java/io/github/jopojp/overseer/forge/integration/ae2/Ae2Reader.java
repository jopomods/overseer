package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import appeng.me.InWorldGridNode;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.ControllerState;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Crafting;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Energy;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Kind;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Storage;
import io.github.jopojp.overseer.core.provider.Stack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The only class that reads Applied Energistics 2 objects (AE2 15 for 1.20.1). Server thread
 * only. Copies everything into {@link Ae2Model}; no AE2 object escapes.
 *
 * <p>AE2 has no public list of grids; {@link TickHandler#getGridList()} is the internal one
 * every grid registers with. Grids without a controller are found through registrations, or
 * reported as hidden from {@link #HIDDEN_MIN_NODES} nodes on (ADR-0011). Node positions come
 * from the internal {@code InWorldGridNode}.
 */
final class Ae2Reader {
    private static final Comparator<ControllerBlockEntity> LOWEST = Comparator
            .comparing((ControllerBlockEntity c) -> dimension(c.getLevel()))
            .thenComparingInt(c -> c.getBlockPos().getX())
            .thenComparingInt(c -> c.getBlockPos().getY())
            .thenComparingInt(c -> c.getBlockPos().getZ());

    private Ae2Reader() {}

    /** Grids without a controller and registration show up (hidden) from this size on. */
    static final int HIDDEN_MIN_NODES = 5;

    static List<Ae2Model.Grid> readAll(MinecraftServer server, Map<String, UUID> registrations) {
        return readAll(server, registrations, new StackCache(), false);
    }

    /**
     * @param registrations registered grid positions ({@code dimension@x,y,z}) and their owners
     * @param keys          ids and names of AE2 keys, kept between collections
     * @param scanInventory copy the full inventory of identified grids
     */
    static List<Ae2Model.Grid> readAll(MinecraftServer server, Map<String, UUID> registrations, StackCache keys,
            boolean scanInventory) {
        StackCache scan = scanInventory ? keys : null;
        var registered = registeredGrids(server, registrations);
        var result = new ArrayList<Ae2Model.Grid>();
        for (Grid grid : TickHandler.instance().getGridList()) {
            if (grid == null || grid.isEmpty()) continue;
            var controllers = grid.getMachines(ControllerBlockEntity.class);
            ControllerBlockEntity id = controllers.stream()
                    .filter(c -> c.getLevel() != null && !c.isRemoved())
                    .min(LOWEST)
                    .orElse(null);
            if (id != null) {
                IGridNode node = id.getMainNode().getNode();
                result.add(read(server, grid, dimension(id.getLevel()), id.getBlockPos(),
                        node == null ? null : node.getOwningPlayerProfileId(), controllers.size(), Kind.CONTROLLER,
                        scan, keys));
                continue;
            }
            var registration = registered.get(grid);
            if (registration != null) {
                result.add(read(server, grid, registration.dimension(), registration.pos(), registration.owner(), 0,
                        Kind.REGISTERED, scan, keys));
            } else if (grid.size() >= HIDDEN_MIN_NODES) {
                var lowest = lowestNode(grid);
                if (lowest != null) {
                    result.add(read(server, grid, lowest.dimension(), lowest.pos(), null, 0, Kind.HIDDEN, null, keys));
                }
            }
        }
        return result;
    }

    private record Located(String dimension, BlockPos pos, UUID owner) {}

    /** Finds the grid at each registered position. Never loads chunks. */
    private static Map<Grid, Located> registeredGrids(MinecraftServer server, Map<String, UUID> registrations) {
        var result = new IdentityHashMap<Grid, Located>();
        registrations.forEach((id, owner) -> {
            int at = id.lastIndexOf('@');
            if (at < 0) return;
            String dimension = id.substring(0, at);
            String[] xyz = id.substring(at + 1).split(",");
            ResourceLocation location = ResourceLocation.tryParse(dimension);
            if (xyz.length != 3 || location == null) return;
            BlockPos pos;
            try {
                pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            } catch (NumberFormatException e) {
                return;
            }
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
            if (level == null || !level.hasChunkAt(pos)) return;
            IGridNode node = nodeAt(level, pos);
            if (node != null && node.getGrid() instanceof Grid grid) result.putIfAbsent(grid, new Located(dimension, pos, owner));
        });
        return result;
    }

    /** The grid node of the block (or cable bus) at a position, if any. Server thread, loaded chunk. */
    static IGridNode nodeAt(Level level, BlockPos pos) {
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host == null) return null;
        IGridNode node = host.getGridNode(null);
        for (int i = 0; node == null && i < DIRECTIONS.length; i++) node = host.getGridNode(DIRECTIONS[i]);
        return node;
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Lowest in-world node: a position that belongs to the grid, for hidden grids. */
    private static Located lowestNode(Grid grid) {
        Located best = null;
        for (IGridNode node : grid.getNodes()) {
            if (!(node instanceof InWorldGridNode inWorld) || node.getLevel() == null) continue;
            var candidate = new Located(dimension(node.getLevel()), inWorld.getLocation(), null);
            if (best == null || compare(candidate, best) < 0) best = candidate;
        }
        return best;
    }

    private static int compare(Located a, Located b) {
        int c = a.dimension().compareTo(b.dimension());
        if (c != 0) return c;
        c = Integer.compare(a.pos().getX(), b.pos().getX());
        if (c != 0) return c;
        c = Integer.compare(a.pos().getY(), b.pos().getY());
        return c != 0 ? c : Integer.compare(a.pos().getZ(), b.pos().getZ());
    }

    private static Ae2Model.Grid read(MinecraftServer server, Grid grid, String dimension, BlockPos pos, UUID owner,
            int controllers, Kind kind, StackCache scan, StackCache keys) {

        IEnergyService e = grid.getEnergyService();
        var energy = new Energy(e.getStoredPower(), e.getMaxStoredPower(), e.getAvgPowerUsage(),
                e.getAvgPowerInjection(), e.getIdlePowerUsage(), e.isNetworkPowered());

        int itemTypes = 0;
        int fluidTypes = 0;
        int otherTypes = 0;
        long items = 0;
        long fluids = 0;
        var cached = grid.getStorageService().getCachedInventory();
        List<Stack> inventory = scan == null ? null : new ArrayList<>(cached.size());
        for (Object2LongMap.Entry<AEKey> entry : cached) {
            AEKey key = entry.getKey();
            if (inventory != null && entry.getLongValue() > 0) inventory.add(scan.stack(key, entry.getLongValue()));
            if (key instanceof AEItemKey) {
                itemTypes++;
                items = saturatedAdd(items, entry.getLongValue());
            } else if (key instanceof AEFluidKey) {
                fluidTypes++;
                fluids = saturatedAdd(fluids, entry.getLongValue());
            } else {
                otherTypes++;
            }
        }
        // AE2 on Forge counts fluids in mB already (AEFluidKey.AMOUNT_BUCKET = 1000).
        var storage = new Storage(itemTypes, items, fluidTypes, fluids, otherTypes);

        int cpus = 0;
        int busy = 0;
        long cpuStorage = 0;
        int coProcessors = 0;
        var cpuList = new ArrayList<Ae2Model.Cpu>();
        String gridId = dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            cpus++;
            if (cpu.isBusy()) busy++;
            cpuStorage += cpu.getAvailableStorage();
            coProcessors += cpu.getCoProcessors();
            if (kind != Kind.HIDDEN) cpuList.add(cpu(cpu, gridId, cpus - 1, keys));
        }
        // Stable order: by id, so the UI does not reshuffle.
        cpuList.sort(Comparator.comparing(Ae2Model.Cpu::id));

        IPathingService pathing = grid.getPathingService();
        return new Ae2Model.Grid(dimension, pos.getX(), pos.getY(), pos.getZ(), owner,
                ownerName(server, owner), grid.size(), controllers,
                ControllerState.valueOf(pathing.getControllerState().name()), pathing.isNetworkBooting(),
                pathing.getUsedChannels(), energy, storage, new Crafting(cpus, busy, cpuStorage, coProcessors, cpuList), kind,
                inventory);
    }

    /**
     * Ids and names of AE2 keys, computed once per key: building them for every key on every
     * scan would cost more than the scan itself. Server thread only.
     */
    static final class StackCache {
        private static final int MAX_KEYS = 100_000;
        private final Map<AEKey, Stack> known = new HashMap<>();

        Stack stack(AEKey key, long amount) {
            Stack template = known.get(key);
            if (template == null) {
                if (known.size() >= MAX_KEYS) known.clear();
                template = new Stack(id(key), kind(key), key.getDisplayName().getString(), 0);
                known.put(key, template);
            }
            return new Stack(template.key(), template.kind(), template.name(), amount);
        }

        private static String kind(AEKey key) {
            if (key instanceof AEItemKey) return "item";
            if (key instanceof AEFluidKey) return "fluid";
            return "other";
        }

        /**
         * {@code item:<id>} or {@code fluid:<id>}, plus {@code #<hash>} of the NBT. The NBT hash is
         * order-independent and stable across restarts. Other key types:
         * {@code other:<type id>/<id>}.
         */
        static String id(AEKey key) {
            CompoundTag tag = null;
            String prefix;
            if (key instanceof AEItemKey item) {
                prefix = "item:";
                tag = item.getTag();
            } else if (key instanceof AEFluidKey fluid) {
                prefix = "fluid:";
                tag = fluid.getTag();
            } else {
                prefix = "other:" + key.getType().getId() + "/";
            }
            String id = prefix + key.getId();
            return tag == null || tag.isEmpty() ? id : id + "#" + Integer.toHexString(tag.hashCode());
        }
    }

    private static Ae2Model.Cpu cpu(ICraftingCPU cpu, String gridId, int index, StackCache keys) {
        String id = gridId + "#" + index;
        if (cpu instanceof CraftingCPUCluster cluster && cluster.getLevel() != null) {
            BlockPos min = cluster.getBoundsMin();
            id = dimension(cluster.getLevel()) + "@" + min.getX() + "," + min.getY() + "," + min.getZ();
        }
        Component name = cpu.getName();
        Ae2Model.Job job = null;
        CraftingJobStatus status = cpu.getJobStatus();
        if (status != null && status.crafting() != null) {
            Stack what = keys.stack(status.crafting().what(), status.crafting().amount());
            job = new Ae2Model.Job(what.key(), what.kind(), what.name(), what.amount(), status.totalItems(),
                    status.progress(), status.elapsedTimeNanos());
        }
        return new Ae2Model.Cpu(id, name == null ? null : name.getString(), cpu.getAvailableStorage(),
                cpu.getCoProcessors(), cpu.getSelectionMode().name().toLowerCase(java.util.Locale.ROOT), job);
    }

    private static String dimension(Level level) {
        return level == null ? "" : level.dimension().location().toString();
    }

    private static String ownerName(MinecraftServer server, UUID owner) {
        if (owner == null || server.getProfileCache() == null) return null;
        return server.getProfileCache().get(owner).map(p -> p.getName()).orElse(null);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
