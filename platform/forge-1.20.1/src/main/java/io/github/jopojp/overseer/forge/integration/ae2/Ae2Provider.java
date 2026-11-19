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
    private final Teams teams;
    private final Supplier<Map<String, UUID>> registrations;
    private final Supplier<Map<String, Set<String>>> watches;
    private java.util.function.BooleanSupplier placerAccess = () -> false;
    private volatile Ae2Mapping.AccessIndex access = Ae2Mapping.AccessIndex.EMPTY;
    private final Ae2Reader.StackCache stacks = new Ae2Reader.StackCache();
    /** Placers per grid id, refreshed with every inventory scan (10 s); walking all nodes costs. */
    private final Map<String, Set<UUID>> placers = new java.util.HashMap<>();
    private long collections;
    /** Patterns per grid id from the last scan. Server thread. */
    private final Map<String, List<Ae2Model.Pattern>> patterns = new java.util.HashMap<>();

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
        grids = withCachedPatterns(grids);
        access = Ae2Mapping.AccessIndex.of(grids, teams::teammates, placerAccess.getAsBoolean());
        return Ae2Mapping.snapshot(at, grids);
    }

    /**
     * Patterns are read with the inventory scan (every 10 s) and sent with every snapshot, because
     * the live table of a snapshot replaces the previous one.
     */
    private List<Ae2Model.Grid> withCachedPatterns(List<Ae2Model.Grid> grids) {
        var result = new java.util.ArrayList<Ae2Model.Grid>(grids.size());
        for (Ae2Model.Grid g : grids) {
            if (g.patterns() != null) {
                patterns.put(g.id(), g.patterns());
                result.add(g);
            } else {
                result.add(g.withPatterns(patterns.get(g.id())));
            }
        }
        patterns.keySet().retainAll(grids.stream().map(Ae2Model.Grid::id).toList());
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
        Set<String> offered = Set.copyOf(types);
        var context = new Ae2Writer.Context(server, registrations, teams);
        return new ActionHandler() {
            @Override
            public Set<String> types() {
                return offered;
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
                        default -> ActionResult.error(ActionResult.Status.INVALID, "Unknown action.");
                    };
                } catch (Ae2Actions.Invalid e) {
                    return ActionResult.error(ActionResult.Status.INVALID, e.getMessage());
                }
            }
        };
    }
}
