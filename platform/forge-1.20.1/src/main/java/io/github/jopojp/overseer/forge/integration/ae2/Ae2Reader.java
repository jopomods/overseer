package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.ControllerState;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Crafting;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Energy;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Storage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * The only class that reads Applied Energistics 2 objects (AE2 15 for 1.20.1). Server thread
 * only. Copies everything into {@link Ae2Model}; no AE2 object escapes.
 *
 * <p>AE2 has no public list of grids; {@link TickHandler#getGridList()} is the internal one
 * every grid registers with. Grids without a controller are skipped (ADR-0011).
 */
final class Ae2Reader {
    private static final Comparator<ControllerBlockEntity> LOWEST = Comparator
            .comparing((ControllerBlockEntity c) -> dimension(c.getLevel()))
            .thenComparingInt(c -> c.getBlockPos().getX())
            .thenComparingInt(c -> c.getBlockPos().getY())
            .thenComparingInt(c -> c.getBlockPos().getZ());

    private Ae2Reader() {}

    static List<Ae2Model.Grid> readAll(MinecraftServer server) {
        var result = new ArrayList<Ae2Model.Grid>();
        for (Grid grid : TickHandler.instance().getGridList()) {
            if (grid == null || grid.isEmpty()) continue;
            var controllers = grid.getMachines(ControllerBlockEntity.class);
            ControllerBlockEntity id = controllers.stream()
                    .filter(c -> c.getLevel() != null && !c.isRemoved())
                    .min(LOWEST)
                    .orElse(null);
            if (id == null) continue;
            result.add(read(server, grid, id, controllers.size()));
        }
        return result;
    }

    private static Ae2Model.Grid read(MinecraftServer server, Grid grid, ControllerBlockEntity id, int controllers) {
        BlockPos pos = id.getBlockPos();
        IGridNode node = id.getMainNode().getNode();
        UUID owner = node == null ? null : node.getOwningPlayerProfileId();

        IEnergyService e = grid.getEnergyService();
        var energy = new Energy(e.getStoredPower(), e.getMaxStoredPower(), e.getAvgPowerUsage(),
                e.getAvgPowerInjection(), e.getIdlePowerUsage(), e.isNetworkPowered());

        int itemTypes = 0;
        int fluidTypes = 0;
        int otherTypes = 0;
        long items = 0;
        long fluids = 0;
        for (Object2LongMap.Entry<AEKey> entry : grid.getStorageService().getCachedInventory()) {
            AEKey key = entry.getKey();
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
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            cpus++;
            if (cpu.isBusy()) busy++;
            cpuStorage += cpu.getAvailableStorage();
            coProcessors += cpu.getCoProcessors();
        }

        IPathingService pathing = grid.getPathingService();
        return new Ae2Model.Grid(dimension(id.getLevel()), pos.getX(), pos.getY(), pos.getZ(), owner,
                ownerName(server, owner), grid.size(), controllers,
                ControllerState.valueOf(pathing.getControllerState().name()), pathing.isNetworkBooting(),
                pathing.getUsedChannels(), energy, storage, new Crafting(cpus, busy, cpuStorage, coProcessors));
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
