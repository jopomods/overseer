package io.github.jopojp.overseer.forge.integration.ae2;

import io.github.jopojp.overseer.core.action.ActionHandler;
import io.github.jopojp.overseer.core.action.ActionRequest;
import io.github.jopojp.overseer.core.action.ActionResult;
import io.github.jopojp.overseer.core.action.Actor;
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
    private final Ae2Writer.Context context;
    private final Teams teams;
    private final Supplier<Map<String, UUID>> registrations;
    private final Supplier<Map<String, Set<String>>> watches;
    private Supplier<Map<String, Map<String, io.github.jopojp.overseer.core.storage.WatchStore.Restock>>> restockTargets = Map::of;
    private java.util.function.BooleanSupplier restock = () -> true;
    private java.util.function.BooleanSupplier placerAccess = () -> false;
    private java.util.function.BooleanSupplier remoteCrafting = () -> true;
    private java.util.function.BooleanSupplier flow = () -> true;
    private volatile Ae2Mapping.AccessIndex access = Ae2Mapping.AccessIndex.EMPTY;
    private final Ae2Reader.StackCache stacks = new Ae2Reader.StackCache();
    private final Ae2Crafting crafting = new Ae2Crafting(stacks, System::nanoTime);
    private final Ae2Restocker restocker = new Ae2Restocker(stacks, System::nanoTime);
    /** Placers per grid id, refreshed with every inventory scan (10 s); walking all nodes costs. */
    private final Map<String, Set<UUID>> placers = new java.util.HashMap<>();
    private long collections;
    /** Patterns per grid id from the last scan. Server thread. */
    private final Map<String, List<Ae2Model.Pattern>> patterns = new java.util.HashMap<>();
    /** P2P tunnels per grid id from the last scan. Server thread. */
    private final Map<String, List<Ae2Model.Tunnel>> tunnels = new java.util.HashMap<>();
    /** Devices without channel per grid id from the last scan. Server thread. */
    private final Map<String, List<Ae2Model.Offline>> offline = new java.util.HashMap<>();
    /** Jobs without progress (ADR-0017 diagnosis). Server thread. */
    private final Ae2Mapping.StallTracker stalls = new Ae2Mapping.StallTracker();

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
        this.context = new Ae2Writer.Context(server, registrations, teams);
    }

    /** Full inventory scan every 10th collection: every 10 s (ADR-0011). */
    static final int SCAN_EVERY = 10;

    /** Players who placed a node of a grid may view it (server setting {@code access.ae2PlacerAccess}). */
    public Ae2Provider placerAccess(java.util.function.BooleanSupplier enabled) {
        this.placerAccess = enabled;
        return this;
    }

    /** Count what devices move into and out of networks (server setting {@code ae2.flow}, ADR-0020). */
    public Ae2Provider flow(java.util.function.BooleanSupplier enabled) {
        this.flow = enabled;
        return this;
    }

    /**
     * Auto-crafting of watched items (ADR-0021; server setting {@code actions.ae2Restock}, only with
     * remote crafting on).
     *
     * @param targets items to keep in stock per grid id, then stack key; read every collection, must be cheap
     */
    public Ae2Provider restock(java.util.function.BooleanSupplier enabled,
            Supplier<Map<String, Map<String, io.github.jopojp.overseer.core.storage.WatchStore.Restock>>> targets) {
        this.restock = enabled;
        this.restockTargets = targets;
        return this;
    }

    private boolean restocking() {
        return restock.getAsBoolean() && remoteCrafting.getAsBoolean();
    }

    /** Players may plan and start crafting jobs from the dashboard (server setting {@code actions.ae2Crafting}). */
    public Ae2Provider remoteCrafting(java.util.function.BooleanSupplier enabled) {
        this.remoteCrafting = enabled;
        return this;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        crafting.tick();
        boolean scan = collections++ % SCAN_EVERY == 0;
        boolean flows = flow.getAsBoolean();
        FlowRecorder.configure(flows, Thread.currentThread());
        var storages = new java.util.IdentityHashMap<Object, String>();
        List<Ae2Model.Grid> grids = Ae2Reader.readAll(server, registrations.get(), watches.get(), stacks, scan,
                placerAccess.getAsBoolean() ? placers : null, storages);
        Map<String, List<io.github.jopojp.overseer.core.provider.Flow>> moved = flows ? flows(storages) : Map.of();
        grids = withCachedPatterns(grids);
        access = Ae2Mapping.AccessIndex.of(grids, teams::teammates, placerAccess.getAsBoolean());
        restocker.tick(context, restocking() ? restockTargets.get() : Map.of(), at.wall());
        return Ae2Mapping.snapshot(at, grids, stalls.update(grids, at), moved, restocker.states());
    }

    /** What was recorded since the last collection, per identified grid; other networks are dropped. */
    private Map<String, List<io.github.jopojp.overseer.core.provider.Flow>> flows(Map<Object, String> storages) {
        var result = new java.util.HashMap<String, List<io.github.jopojp.overseer.core.provider.Flow>>();
        var devices = new java.util.IdentityHashMap<Object, Ae2Reader.FlowDevice>();
        FlowRecorder.drain().forEach((storage, bySource) -> {
            String grid = storages.get(storage);
            if (grid == null) return;
            var list = result.computeIfAbsent(grid, g -> new java.util.ArrayList<>());
            bySource.forEach((who, byKey) -> {
                var device = devices.computeIfAbsent(who, Ae2Reader::flowDevice);
                byKey.forEach((key, counts) -> list.add(new io.github.jopojp.overseer.core.provider.Flow(device.id(),
                        device.kind(), device.name(), stacks.stack(key, 0), counts[0], counts[1])));
            });
        });
        return result;
    }

    /**
     * Patterns are read with the inventory scan (every 10 s) and sent with every snapshot, because
     * the live table of a snapshot replaces the previous one.
     */
    private List<Ae2Model.Grid> withCachedPatterns(List<Ae2Model.Grid> grids) {
        var result = new java.util.ArrayList<Ae2Model.Grid>(grids.size());
        for (Ae2Model.Grid g : grids) {
            if (g.patterns() != null) patterns.put(g.id(), g.patterns());
            else g = g.withPatterns(patterns.get(g.id()));
            // Booting grids are not checked: keep the last answer until the next scan.
            if (g.offline() != null) offline.put(g.id(), g.offline());
            else g = g.withOffline(offline.get(g.id()));
            if (g.tunnels() != null) tunnels.put(g.id(), g.tunnels());
            else g = g.withTunnels(tunnels.get(g.id()));
            result.add(g);
        }
        var ids = grids.stream().map(Ae2Model.Grid::id).toList();
        patterns.keySet().retainAll(ids);
        offline.keySet().retainAll(ids);
        tunnels.keySet().retainAll(ids);
        return result;
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

    /** AE2 from the dashboard (ADR-0017): the grid's owner, their party and operators. */
    @Override
    public ActionHandler actions() {
        var types = new java.util.HashSet<>(Ae2Actions.BASE_TYPES);
        if (Ae2Compat.canSuspend()) types.add(Ae2Actions.CPU_SUSPEND);
        Set<String> base = Set.copyOf(types);
        types.addAll(Ae2Actions.CRAFT_TYPES);
        Set<String> withCrafting = Set.copyOf(types);
        types.add(Ae2Actions.RESTOCK);
        Set<String> withRestock = Set.copyOf(types);
        return new ActionHandler() {
            /** Read on every request, so the server setting applies without a restart. */
            @Override
            public Set<String> types() {
                if (!remoteCrafting.getAsBoolean()) return base;
                return restock.getAsBoolean() ? withRestock : withCrafting;
            }

            /** Plans finish later: AE2 computes them on a thread of its own (ADR-0019). */
            @Override
            public java.util.concurrent.CompletionStage<ActionResult> start(Actor actor, ActionRequest request) {
                if (!request.type().equals(Ae2Actions.CRAFT_PLAN)) {
                    return java.util.concurrent.CompletableFuture.completedFuture(perform(actor, request));
                }
                try {
                    if (!remoteCrafting.getAsBoolean()) {
                        return java.util.concurrent.CompletableFuture.completedFuture(ActionResult.error(
                                ActionResult.Status.DENIED, "Crafting from the dashboard is switched off on this server."));
                    }
                    return crafting.plan(context, actor, Ae2Actions.gridId(request.target()),
                            Ae2Actions.craftPlan(request.params()));
                } catch (Ae2Actions.Invalid e) {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            ActionResult.error(ActionResult.Status.INVALID, e.getMessage()));
                }
            }

            @Override
            public boolean canPerform(UUID player, ResourceRef target, String type) {
                return target.type().equals("grid") && access.canAct(player, target);
            }

            @Override
            public ActionResult perform(Actor actor, ActionRequest request) {
                try {
                    String grid = Ae2Actions.gridId(request.target());
                    var params = request.params();
                    return switch (request.type()) {
                        case Ae2Actions.CPU_CANCEL -> Ae2Writer.cancel(context, actor, grid, Ae2Actions.cpu(params));
                        case Ae2Actions.CPU_SUSPEND -> Ae2Writer.suspend(context, actor, grid, Ae2Actions.cpuSuspend(params));
                        case Ae2Actions.CPU_EDIT -> Ae2Writer.editCpu(context, actor, grid, Ae2Actions.cpuEdit(params));
                        case Ae2Actions.DRIVE_EDIT -> Ae2Writer.editDrive(context, actor, grid, Ae2Actions.driveEdit(params));
                        case Ae2Actions.PROVIDER_EDIT -> Ae2Writer.editProvider(context, actor, grid,
                                Ae2Actions.providerEdit(params));
                        case Ae2Actions.BUS_EDIT -> Ae2Writer.editBus(context, actor, grid, stacks, Ae2Actions.busEdit(params));
                        case Ae2Actions.STORAGE_BUS_EDIT -> Ae2Writer.editStorageBus(context, actor, grid, stacks,
                                Ae2Actions.storageBusEdit(params));
                        case Ae2Actions.CRAFT_START -> remoteCrafting.getAsBoolean()
                                ? crafting.start(context, actor, grid, Ae2Actions.craftStart(params))
                                : ActionResult.error(ActionResult.Status.DENIED,
                                        "Crafting from the dashboard is switched off on this server.");
                        case Ae2Actions.CRAFT_PLAN -> ActionResult.error(ActionResult.Status.INVALID,
                                "Plans are computed through start().");
                        case Ae2Actions.RESTOCK -> ActionResult.error(ActionResult.Status.INVALID,
                                "Switch auto-crafting on for a watched item in the watchlist.");
                        default -> ActionResult.error(ActionResult.Status.INVALID, "Unknown action.");
                    };
                } catch (Ae2Actions.Invalid e) {
                    return ActionResult.error(ActionResult.Status.INVALID, e.getMessage());
                }
            }
        };
    }
}
