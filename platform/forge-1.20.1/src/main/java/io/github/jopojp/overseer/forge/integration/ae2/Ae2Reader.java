package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.me.cells.BasicCellInventory;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import appeng.me.InWorldGridNode;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.ControllerState;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Crafting;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Energy;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Kind;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Storage;
import io.github.jopojp.overseer.core.provider.JobLine;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

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
        return readAll(server, registrations, Map.of(), new StackCache(), false, null);
    }

    /**
     * @param registrations registered grid positions ({@code dimension@x,y,z}) and their owners
     * @param keys          ids and names of AE2 keys, kept between collections
     * @param scanInventory copy the full inventory of identified grids
     */
    static List<Ae2Model.Grid> readAll(MinecraftServer server, Map<String, UUID> registrations,
            Map<String, java.util.Set<String>> watches, StackCache keys, boolean scanInventory,
            Map<String, java.util.Set<UUID>> placers) {
        return readAll(server, registrations, watches, keys, scanInventory, placers, null);
    }

    /** @param storages filled with each identified grid's network storage and its id (flows, ADR-0020); may be null */
    static List<Ae2Model.Grid> readAll(MinecraftServer server, Map<String, UUID> registrations,
            Map<String, java.util.Set<String>> watches, StackCache keys, boolean scanInventory,
            Map<String, java.util.Set<UUID>> placers, Map<Object, String> storages) {
        var grids = readGrids(server, registrations, watches, keys, scanInventory, placers, storages);
        if (placers != null) placers.keySet().retainAll(grids.stream().map(Ae2Model.Grid::id).toList());
        return grids;
    }

    /** @param placers cache of placers per grid id, refreshed on scans; {@code null} when not needed */
    private static List<Ae2Model.Grid> readGrids(MinecraftServer server, Map<String, UUID> registrations,
            Map<String, java.util.Set<String>> watches, StackCache keys, boolean scanInventory,
            Map<String, java.util.Set<UUID>> placers, Map<Object, String> storages) {
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
                var read = read(server, grid, dimension(id.getLevel()), id.getBlockPos(), watches, placers,
                        node == null ? null : node.getOwningPlayerProfileId(), controllers.size(), Kind.CONTROLLER,
                        scan, keys);
                result.add(read);
                if (storages != null) storages.put(grid.getStorageService().getInventory(), read.id());
                continue;
            }
            var registration = registered.get(grid);
            if (registration != null) {
                var read = read(server, grid, registration.dimension(), registration.pos(), watches, placers,
                        registration.owner(), 0, Kind.REGISTERED, scan, keys);
                result.add(read);
                if (storages != null) storages.put(grid.getStorageService().getInventory(), read.id());
            } else if (grid.size() >= HIDDEN_MIN_NODES) {
                var lowest = lowestNode(grid);
                if (lowest != null) {
                    result.add(read(server, grid, lowest.dimension(), lowest.pos(), Map.of(), null, null, 0, Kind.HIDDEN, null, keys));
                }
            }
        }
        return result;
    }

    private record Located(String dimension, BlockPos pos, UUID owner) {}

    /** Id and owner of a live grid, as {@link #readAll} identifies it. */
    record Identity(String id, UUID owner) {}

    /**
     * Identifies a live grid like a collection does: by its lowest controller, else by a
     * registration. {@code null} for hidden grids. Server thread.
     */
    static Identity identify(MinecraftServer server, appeng.api.networking.IGrid grid, Map<String, UUID> registrations) {
        if (!(grid instanceof Grid g) || g.isEmpty()) return null;
        ControllerBlockEntity lowest = g.getMachines(ControllerBlockEntity.class).stream()
                .filter(c -> c.getLevel() != null && !c.isRemoved())
                .min(LOWEST)
                .orElse(null);
        if (lowest != null) {
            IGridNode node = lowest.getMainNode().getNode();
            BlockPos pos = lowest.getBlockPos();
            return new Identity(dimension(lowest.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                    node == null ? null : node.getOwningPlayerProfileId());
        }
        Located registration = registeredGrids(server, registrations).get(g);
        if (registration == null) return null;
        BlockPos pos = registration.pos();
        return new Identity(registration.dimension() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                registration.owner());
    }

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

    private static Ae2Model.Grid read(MinecraftServer server, Grid grid, String dimension, BlockPos pos,
            Map<String, java.util.Set<String>> watches, Map<String, java.util.Set<UUID>> placers, UUID owner,
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

        List<Ae2Model.Drive> drives = kind == Kind.HIDDEN ? List.of() : drives(grid, keys);
        List<Ae2Model.StorageBus> storageBuses = kind == Kind.HIDDEN ? List.of() : storageBuses(grid, keys);
        List<Ae2Model.Provider> providers = new ArrayList<>();
        List<Ae2Model.Pattern> patterns = kind == Kind.HIDDEN || scan == null ? null : new ArrayList<>();
        if (kind != Kind.HIDDEN) providers(grid, keys, providers, patterns);
        // Channels are handed out after booting; until then every device would count as offline.
        List<Ae2Model.Bus> buses = kind == Kind.HIDDEN ? List.of() : buses(grid, keys);
        List<Ae2Model.Offline> offline = kind == Kind.HIDDEN || scan == null || pathingBooting(grid) ? null
                : withoutChannel(grid, keys);
        List<Ae2Model.Tunnel> tunnels = kind == Kind.HIDDEN || scan == null ? null : tunnels(grid);

        IPathingService pathing = grid.getPathingService();
        return new Ae2Model.Grid(dimension, pos.getX(), pos.getY(), pos.getZ(), owner,
                ownerName(server, owner), grid.size(), controllers,
                ControllerState.valueOf(pathing.getControllerState().name()), pathing.isNetworkBooting(),
                pathing.getUsedChannels(), energy, storage, new Crafting(cpus, busy, cpuStorage, coProcessors, cpuList), kind,
                inventory, drives, watched(cached, watches.get(gridId), keys),
                placers == null || kind == Kind.HIDDEN ? java.util.Set.of()
                        : scan != null || !placers.containsKey(gridId) ? refresh(placers, gridId, grid)
                        : placers.get(gridId),
                providers, patterns, offline, buses, tunnels, storageBuses);
    }

    /**
     * Ids and names of AE2 keys, computed once per key: building them for every key on every
     * scan would cost more than the scan itself. Server thread only.
     */
    static final class StackCache {
        private static final int MAX_KEYS = 100_000;
        private final Map<AEKey, Stack> known = new HashMap<>();
        private final Map<String, AEKey> byId = new HashMap<>();

        /** The AE2 key of a stack key seen before (in a scan or job), else {@code null}. */
        AEKey byId(String id) {
            return byId.get(id);
        }

        Stack stack(AEKey key, long amount) {
            Stack template = known.get(key);
            if (template == null) {
                if (known.size() >= MAX_KEYS) {
                    known.clear();
                    byId.clear();
                }
                template = new Stack(id(key), kind(key), key.getDisplayName().getString(), 0);
                known.put(key, template);
                byId.put(template.key(), key);
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

    /** {@code dimension@x,y,z} of the lowest corner, or {@code <grid>#<index>} for CPUs without blocks. */
    static String cpuId(ICraftingCPU cpu, String gridId, int index) {
        if (cpu instanceof CraftingCPUCluster cluster && cluster.getLevel() != null) {
            BlockPos min = cluster.getBoundsMin();
            return dimension(cluster.getLevel()) + "@" + min.getX() + "," + min.getY() + "," + min.getZ();
        }
        return gridId + "#" + index;
    }

    private static Ae2Model.Cpu cpu(ICraftingCPU cpu, String gridId, int index, StackCache keys) {
        String id = cpuId(cpu, gridId, index);
        Component name = cpu.getName();
        Ae2Model.Job job = null;
        CraftingJobStatus status = cpu.getJobStatus();
        if (status != null && status.crafting() != null) {
            Stack what = keys.stack(status.crafting().what(), status.crafting().amount());
            var lines = cpu instanceof CraftingCPUCluster cluster ? jobLines(cluster, keys) : List.<JobLine>of();
            job = new Ae2Model.Job(what.key(), what.kind(), what.name(), what.amount(), status.totalItems(),
                    status.progress(), status.elapsedTimeNanos(), lines);
        }
        boolean suspended = job != null && cpu instanceof CraftingCPUCluster cluster
                && Boolean.TRUE.equals(Ae2Compat.suspended(cluster.craftingLogic));
        return new Ae2Model.Cpu(id, name == null ? null : name.getString(), cpu.getAvailableStorage(),
                cpu.getCoProcessors(), cpu.getSelectionMode().name().toLowerCase(java.util.Locale.ROOT), job, index + 1,
                suspended);
    }

    /** Every player who placed a node of the grid (AE2 records it per node). */
    private static java.util.Set<UUID> refresh(Map<String, java.util.Set<UUID>> cache, String gridId, Grid grid) {
        var result = new java.util.HashSet<UUID>();
        for (IGridNode node : grid.getNodes()) {
            UUID placer = node.getOwningPlayerProfileId();
            if (placer != null) result.add(placer);
        }
        var copy = java.util.Set.copyOf(result);
        cache.put(gridId, copy);
        return copy;
    }

    /** Amounts of the watched keys, straight from AE2's cached inventory: cheap, every second. */
    private static Map<String, Long> watched(appeng.api.stacks.KeyCounter cached, java.util.Set<String> keysOfGrid,
            StackCache keys) {
        if (keysOfGrid == null || keysOfGrid.isEmpty()) return Map.of();
        var amounts = new HashMap<String, Long>();
        for (String key : keysOfGrid) {
            AEKey ae = keys.byId(key);
            if (ae != null) amounts.put(key, cached.get(ae));
        }
        return amounts;
    }

    /** Drives and ME chests, add-on drives included (anything implementing {@code IChestOrDrive}). */
    private static List<Ae2Model.Drive> drives(Grid grid, StackCache keys) {
        var result = new ArrayList<Ae2Model.Drive>();
        for (Class<?> type : grid.getMachineClasses()) {
            if (!IChestOrDrive.class.isAssignableFrom(type)) continue;
            for (Object machine : grid.getMachines(type)) {
                if (!(machine instanceof IChestOrDrive drive) || !(machine instanceof BlockEntity be)
                        || be.getLevel() == null || be.isRemoved()) continue;
                var cells = new ArrayList<Ae2Model.Cell>();
                for (int slot = 0; slot < drive.getCellCount(); slot++) {
                    CellState state = drive.getCellStatus(slot);
                    if (state == null || state == CellState.ABSENT) continue;
                    var item = drive.getCellItem(slot);
                    String itemId = item == null ? "" : String.valueOf(ForgeRegistries.ITEMS.getKey(item));
                    long usedBytes = -1, totalBytes = -1, usedTypes = -1, totalTypes = -1;
                    Ae2Model.Partition partition = Ae2Model.Partition.NONE;
                    if (drive.getOriginalCellInventory(slot) instanceof BasicCellInventory basic) {
                        usedBytes = basic.getUsedBytes();
                        totalBytes = basic.getTotalBytes();
                        usedTypes = basic.getStoredItemTypes();
                        totalTypes = basic.getTotalItemTypes();
                        partition = partition(basic.getConfigInventory(), keys,
                                basic.getPartitionListMode() == appeng.api.config.IncludeExclude.BLACKLIST, basic.isFuzzy());
                    }
                    cells.add(new Ae2Model.Cell(slot, itemId, state.name().toLowerCase(java.util.Locale.ROOT), usedBytes,
                            totalBytes, usedTypes, totalTypes, partition));
                }
                BlockPos pos = be.getBlockPos();
                Integer priority = machine instanceof appeng.helpers.IPriorityHost host ? host.getPriority() : null;
                result.add(new Ae2Model.Drive(dimension(be.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                        String.valueOf(ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock())), cells, priority));
            }
        }
        result.sort(Comparator.comparing(Ae2Model.Drive::id));
        return result;
    }

    /**
     * Pattern providers of a grid, blocks and parts, add-on providers included (anything implementing
     * {@code PatternProviderLogicHost}). Patterns only when {@code patterns} is given (scans).
     */
    private static void providers(Grid grid, StackCache keys, List<Ae2Model.Provider> out,
            List<Ae2Model.Pattern> patterns) {
        for (Class<?> type : grid.getMachineClasses()) {
            if (!PatternProviderLogicHost.class.isAssignableFrom(type)) continue;
            for (Object machine : grid.getMachines(type)) {
                if (!(machine instanceof PatternProviderLogicHost host)) continue;
                BlockEntity be = host.getBlockEntity();
                if (be == null || be.getLevel() == null || be.isRemoved()) continue;
                String id = providerId(host);
                var logic = host.getLogic();
                var config = logic.getConfigManager();
                var group = logic.getTerminalGroup();
                Component custom = host instanceof net.minecraft.world.Nameable named ? named.getCustomName() : null;
                var available = logic.getAvailablePatterns();
                out.add(new Ae2Model.Provider(id, providerBlock(host), custom == null ? null : custom.getString(),
                        group == null || group.name() == null ? "" : group.name().getString(),
                        group == null || group.icon() == null ? null : StackCache.id(group.icon()),
                        logic.getPriority(),
                        config.getSetting(appeng.api.config.Settings.BLOCKING_MODE) == appeng.api.config.YesNo.YES,
                        lower(config.getSetting(appeng.api.config.Settings.LOCK_CRAFTING_MODE).name()),
                        lower(logic.getCraftingLockedReason().name()),
                        config.getSetting(appeng.api.config.Settings.PATTERN_ACCESS_TERMINAL) == appeng.api.config.YesNo.YES,
                        available.size(), ProviderIcons.id(host)));
                if (patterns == null) continue;
                for (var pattern : available) {
                    var output = pattern.getPrimaryOutput();
                    if (output == null) continue;
                    var inputs = new ArrayList<Stack>();
                    for (var input : pattern.getInputs()) {
                        var possible = input.getPossibleInputs();
                        if (possible.length == 0) continue;
                        inputs.add(keys.stack(possible[0].what(), possible[0].amount() * input.getMultiplier()));
                    }
                    patterns.add(new Ae2Model.Pattern(id, keys.stack(output.what(), output.amount()), inputs));
                }
            }
        }
        out.sort(Comparator.comparing(Ae2Model.Provider::id));
        if (patterns != null) patterns.sort(Comparator.comparing(Ae2Model.Pattern::provider));
    }

    /** A device that moved items: id within its network, kind and display name (ADR-0020). */
    record FlowDevice(String id, String kind, String name) {}

    static final FlowDevice OTHER = new FlowDevice("other", "other", "Other");

    /**
     * Names what acted on a network storage: a player, a part (bus, interface, provider, ...), a block
     * (interface, provider, crafting CPU, ...), or {@link #OTHER}. Server thread, once per collection.
     */
    static FlowDevice flowDevice(Object who) {
        if (who instanceof net.minecraft.world.entity.player.Player player) {
            return new FlowDevice("player:" + player.getUUID(), "player", player.getGameProfile().getName());
        }
        if (who instanceof appeng.parts.AEBasePart part && part.getBlockEntity() != null
                && part.getBlockEntity().getLevel() != null) {
            BlockPos pos = part.getBlockEntity().getBlockPos();
            String id = dimension(part.getBlockEntity().getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + (part.getSide() == null ? "" : "/" + part.getSide().getSerializedName());
            Component custom = part.getCustomName();
            var item = part.getPartItem() == null ? null : part.getPartItem().asItem();
            String name = custom != null ? custom.getString()
                    : item == null ? id : new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
            return new FlowDevice(id, partKind(part, item), name);
        }
        if (who instanceof BlockEntity be && be.getLevel() != null) {
            if (be instanceof appeng.blockentity.crafting.CraftingBlockEntity crafting && crafting.getCluster() != null) {
                var cluster = crafting.getCluster();
                BlockPos min = cluster.getBoundsMin();
                Component name = cluster.getName();
                return new FlowDevice(dimension(be.getLevel()) + "@" + min.getX() + "," + min.getY() + "," + min.getZ(),
                        "crafting", name == null ? "" : name.getString());
            }
            BlockPos pos = be.getBlockPos();
            String id = dimension(be.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            Component custom = be instanceof net.minecraft.world.Nameable named ? named.getCustomName() : null;
            String name = custom != null ? custom.getString() : be.getBlockState().getBlock().getName().getString();
            String kind = be instanceof PatternProviderLogicHost ? "pattern_provider"
                    : be instanceof appeng.helpers.InterfaceLogicHost ? "interface"
                    : String.valueOf(ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock())).replaceFirst("^[^:]*:", "");
            return new FlowDevice(id, kind, name);
        }
        return OTHER;
    }

    private static String partKind(appeng.parts.AEBasePart part, net.minecraft.world.item.Item item) {
        if (part instanceof appeng.parts.automation.ImportBusPart) return "import_bus";
        if (part instanceof appeng.parts.automation.ExportBusPart) return "export_bus";
        if (part instanceof appeng.parts.storagebus.StorageBusPart) return "storage_bus";
        if (part instanceof PatternProviderLogicHost) return "pattern_provider";
        if (part instanceof appeng.helpers.InterfaceLogicHost) return "interface";
        if (part instanceof appeng.parts.reporting.AbstractTerminalPart) return "terminal";
        return item == null ? "part" : String.valueOf(ForgeRegistries.ITEMS.getKey(item)).replaceFirst("^[^:]*:", "");
    }

    /** Import and export buses of a grid, add-on buses included (anything extending {@code IOBusPart}). */
    private static List<Ae2Model.Bus> buses(Grid grid, StackCache keys) {
        var result = new ArrayList<Ae2Model.Bus>();
        for (Class<?> type : grid.getMachineClasses()) {
            if (!appeng.parts.automation.IOBusPart.class.isAssignableFrom(type)) continue;
            for (Object machine : grid.getMachines(type)) {
                if (!(machine instanceof appeng.parts.automation.IOBusPart bus)) continue;
                BlockEntity be = bus.getBlockEntity();
                if (be == null || be.getLevel() == null || be.isRemoved() || bus.getSide() == null) continue;
                BlockPos pos = be.getBlockPos();
                String id = dimension(be.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "/"
                        + bus.getSide().getSerializedName();
                String kind = bus instanceof appeng.parts.automation.ImportBusPart ? "import"
                        : bus instanceof appeng.parts.automation.ExportBusPart ? "export"
                        : bus.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
                // What the bus faces, as the player sees it; nothing in an unloaded chunk.
                String[] facing = facing(be, bus.getSide());
                String target = facing[0];
                String targetBlock = facing[1];
                var filter = new ArrayList<Stack>();
                var config = bus.getConfig();
                for (int i = 0; i < config.size(); i++) {
                    AEKey key = config.getKey(i);
                    if (key != null) filter.add(keys.stack(key, 0));
                }
                var upgrades = new java.util.TreeMap<String, Integer>();
                var cards = bus.getUpgrades();
                for (int i = 0; i < cards.size(); i++) {
                    var card = cards.getStackInSlot(i);
                    if (!card.isEmpty()) {
                        upgrades.merge(String.valueOf(ForgeRegistries.ITEMS.getKey(card.getItem())), card.getCount(), Integer::sum);
                    }
                }
                var settings = bus.getConfigManager();
                Boolean craftOnly = null;
                String scheduling = null;
                int requested = 0;
                if (bus instanceof appeng.parts.automation.ExportBusPart export) {
                    if (settings.hasSetting(appeng.api.config.Settings.CRAFT_ONLY)) {
                        craftOnly = settings.getSetting(appeng.api.config.Settings.CRAFT_ONLY) == appeng.api.config.YesNo.YES;
                    }
                    if (settings.hasSetting(appeng.api.config.Settings.SCHEDULING_MODE)) {
                        scheduling = lower(settings.getSetting(appeng.api.config.Settings.SCHEDULING_MODE).name());
                    }
                    requested = export.getRequestedJobs().size();
                }
                Component custom = bus.getCustomName();
                IGridNode node = bus.getGridNode();
                result.add(new Ae2Model.Bus(id, providerBlockOf(bus), kind, custom == null ? null : custom.getString(), target,
                        targetBlock, filter, upgrades, lower(bus.getRSMode().name()), craftOnly, scheduling, requested,
                        node != null && node.isActive()));
            }
        }
        result.sort(Comparator.comparing(Ae2Model.Bus::id));
        return result;
    }

    private static Ae2Model.Partition partition(appeng.util.ConfigInventory config, StackCache keys, boolean exclude,
            boolean fuzzy) {
        var list = new ArrayList<Stack>();
        for (int i = 0; i < config.size(); i++) {
            AEKey key = config.getKey(i);
            if (key != null) list.add(keys.stack(key, 0));
        }
        return list.isEmpty() ? Ae2Model.Partition.NONE : new Ae2Model.Partition(list, exclude, fuzzy);
    }

    /** Storage buses of a grid with their priority, partition and access. */
    private static List<Ae2Model.StorageBus> storageBuses(Grid grid, StackCache keys) {
        var result = new ArrayList<Ae2Model.StorageBus>();
        for (Class<?> type : grid.getMachineClasses()) {
            if (!appeng.parts.storagebus.StorageBusPart.class.isAssignableFrom(type)) continue;
            for (Object machine : grid.getMachines(type)) {
                if (!(machine instanceof appeng.parts.storagebus.StorageBusPart bus)) continue;
                BlockEntity be = bus.getBlockEntity();
                if (be == null || be.getLevel() == null || be.isRemoved() || bus.getSide() == null) continue;
                BlockPos pos = be.getBlockPos();
                String id = dimension(be.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "/"
                        + bus.getSide().getSerializedName();
                String[] facing = facing(be, bus.getSide());
                boolean exclude = false;
                boolean fuzzy = false;
                var cards = bus.getUpgrades();
                for (int i = 0; i < cards.size(); i++) {
                    var card = cards.getStackInSlot(i);
                    if (card.is(appeng.core.definitions.AEItems.INVERTER_CARD.asItem())) exclude = true;
                    if (card.is(appeng.core.definitions.AEItems.FUZZY_CARD.asItem())) fuzzy = true;
                }
                var settings = bus.getConfigManager();
                String access = settings.hasSetting(appeng.api.config.Settings.ACCESS)
                        ? lower(settings.getSetting(appeng.api.config.Settings.ACCESS).name()) : "read_write";
                boolean extractableOnly = settings.hasSetting(appeng.api.config.Settings.STORAGE_FILTER)
                        && settings.getSetting(appeng.api.config.Settings.STORAGE_FILTER) == appeng.api.config.StorageFilter.EXTRACTABLE_ONLY;
                Component custom = bus.getCustomName();
                IGridNode node = bus.getGridNode();
                result.add(new Ae2Model.StorageBus(id, providerBlockOf(bus), custom == null ? null : custom.getString(),
                        facing[0], facing[1], bus.getPriority(), partition(bus.getConfig(), keys, exclude, fuzzy), access,
                        extractableOnly, node != null && node.isActive()));
            }
        }
        result.sort(Comparator.comparing(Ae2Model.StorageBus::id));
        return result;
    }

    /** Tunnels of a grid at most listed; a P2P wall should not flood the page. */
    static final int MAX_TUNNELS = 1000;

    /**
     * The P2P tunnels whose own node is in this grid. ME tunnels also have an outer node in the
     * network they carry; they are listed only where they were placed.
     */
    private static List<Ae2Model.Tunnel> tunnels(Grid grid) {
        var result = new ArrayList<Ae2Model.Tunnel>();
        var p2p = appeng.util.Platform.p2p();
        for (Class<?> type : grid.getMachineClasses()) {
            if (!appeng.parts.p2p.P2PTunnelPart.class.isAssignableFrom(type)) continue;
            for (Object machine : grid.getMachines(type)) {
                if (result.size() >= MAX_TUNNELS) break;
                if (!(machine instanceof appeng.parts.p2p.P2PTunnelPart<?> tunnel)) continue;
                if (tunnel.getMainNode().getGrid() != grid) continue;
                BlockEntity be = tunnel.getBlockEntity();
                if (be == null || be.getLevel() == null || be.isRemoved() || tunnel.getSide() == null) continue;
                BlockPos pos = be.getBlockPos();
                String id = dimension(be.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "/"
                        + tunnel.getSide().getSerializedName();
                short frequency = tunnel.getFrequency();
                var colors = new java.util.StringJoiner(",");
                for (var color : p2p.toColors(frequency)) colors.add(String.format(java.util.Locale.ROOT, "%06x", color.mediumVariant & 0xffffff));
                String[] facing = facing(be, tunnel.getSide());
                Component custom = tunnel.getCustomName();
                IGridNode node = tunnel.getGridNode();
                result.add(new Ae2Model.Tunnel(id, "item:" + providerBlockOf(tunnel), custom == null ? null : custom.getString(),
                        Short.toUnsignedInt(frequency), p2p.toHexString(frequency), colors.toString(), tunnel.isOutput(),
                        facing[0], facing[1], node != null && node.isActive()));
            }
        }
        result.sort(Comparator.comparing(Ae2Model.Tunnel::id));
        return result;
    }

    /** Name and registry id of the block in front of a part, as the player sees it; empty when there is none or it is unloaded. */
    private static String[] facing(BlockEntity be, Direction side) {
        BlockPos front = be.getBlockPos().relative(side);
        if (!be.getLevel().isLoaded(front)) return new String[] {"", ""};
        var state = be.getLevel().getBlockState(front);
        if (state.isAir()) return new String[] {"", ""};
        BlockEntity facing = be.getLevel().getBlockEntity(front);
        Component custom = facing instanceof net.minecraft.world.Nameable named ? named.getCustomName() : null;
        return new String[] {custom != null ? custom.getString() : state.getBlock().getName().getString(),
                String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock()))};
    }

    private static String providerBlockOf(appeng.api.parts.IPart part) {
        return part.getPartItem() == null ? "" : String.valueOf(ForgeRegistries.ITEMS.getKey(part.getPartItem().asItem()));
    }

    /** Devices of a grid at most reported as without channel; a broken base should not flood the page. */
    static final int MAX_OFFLINE = 500;

    private static boolean pathingBooting(Grid grid) {
        return grid.getPathingService().isNetworkBooting();
    }

    /** Devices that need a channel and have none, with position and the item AE2 shows for them. */
    private static List<Ae2Model.Offline> withoutChannel(Grid grid, StackCache keys) {
        var result = new ArrayList<Ae2Model.Offline>();
        for (IGridNode node : grid.getNodes()) {
            if (!node.hasFlag(appeng.api.networking.GridFlags.REQUIRE_CHANNEL) || node.meetsChannelRequirements()) continue;
            String id = null;
            Object owner = node.getOwner();
            if (owner instanceof appeng.parts.AEBasePart part && part.getBlockEntity() != null
                    && part.getBlockEntity().getLevel() != null) {
                BlockPos pos = part.getBlockEntity().getBlockPos();
                id = dimension(part.getBlockEntity().getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                        + (part.getSide() == null ? "" : "/" + part.getSide().getSerializedName());
            } else if (node instanceof InWorldGridNode inWorld && node.getLevel() != null) {
                BlockPos pos = inWorld.getLocation();
                id = dimension(node.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            }
            if (id == null) continue;
            var visual = node.getVisualRepresentation();
            Stack shown = visual == null ? null : keys.stack(visual, 0);
            result.add(new Ae2Model.Offline(id, shown == null ? "" : shown.key(), shown == null ? "" : shown.name()));
            if (result.size() >= MAX_OFFLINE) break;
        }
        result.sort(Comparator.comparing(Ae2Model.Offline::id));
        return result;
    }

    /** {@code dimension@x,y,z}, plus {@code /<side>} for a part. */
    static String providerId(PatternProviderLogicHost host) {
        BlockEntity be = host.getBlockEntity();
        BlockPos pos = be.getBlockPos();
        String id = dimension(be.getLevel()) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        if (host instanceof appeng.parts.AEBasePart part && part.getSide() != null) {
            id += "/" + part.getSide().getSerializedName();
        }
        return id;
    }

    private static String providerBlock(PatternProviderLogicHost host) {
        if (host instanceof appeng.api.parts.IPart part && part.getPartItem() != null) {
            return String.valueOf(ForgeRegistries.ITEMS.getKey(part.getPartItem().asItem()));
        }
        return String.valueOf(ForgeRegistries.BLOCKS.getKey(host.getBlockEntity().getBlockState().getBlock()));
    }

    private static String lower(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    /** Lines shown per job; large jobs keep the most active ones. */
    static final int MAX_JOB_LINES = 300;

    private static final Comparator<JobLine> MOST_ACTIVE = Comparator
            .comparingLong((JobLine l) -> -l.active())
            .thenComparingLong(l -> -l.pending())
            .thenComparingLong(l -> -l.stored())
            .thenComparing(JobLine::key);

    /** What the job holds and waits for, read like AE2's crafting status screen does. */
    private static List<JobLine> jobLines(CraftingCPUCluster cluster, StackCache keys) {
        var logic = cluster.craftingLogic;
        var all = new KeyCounter();
        logic.getAllItems(all);
        var lines = new ArrayList<JobLine>();
        for (Object2LongMap.Entry<AEKey> entry : all) {
            AEKey key = entry.getKey();
            long stored = logic.getStored(key);
            long active = logic.getWaitingFor(key);
            long pending = logic.getPendingOutputs(key);
            if (stored == 0 && active == 0 && pending == 0) continue;
            Stack s = keys.stack(key, 0);
            lines.add(new JobLine(s.key(), s.kind(), s.name(), stored, active, pending));
        }
        lines.sort(MOST_ACTIVE);
        return lines.size() > MAX_JOB_LINES ? lines.subList(0, MAX_JOB_LINES) : lines;
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
