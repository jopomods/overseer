package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.Setting;
import appeng.api.config.YesNo;
import appeng.api.networking.security.IActionHost;
import appeng.api.parts.IPartHost;
import appeng.api.util.IConfigManager;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.AEBasePart;
import net.minecraft.core.Direction;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import appeng.api.config.Settings;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.helpers.IPriorityHost;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import io.github.jopojp.overseer.core.action.ActionResult;
import io.github.jopojp.overseer.core.action.ActionResult.Change;
import io.github.jopojp.overseer.core.action.ActionResult.Status;
import io.github.jopojp.overseer.core.action.Actor;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.CpuEdit;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.CpuSuspend;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.DriveEdit;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.ProviderEdit;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The only class that changes AE2 state (ADR-0016, ADR-0017). Server thread only. Permissions are
 * checked again here against the live grid: its owner, the owner's party and operators. Uses AE2's
 * own calls where they exist; names go through {@link Ae2Compat}.
 */
final class Ae2Writer {
    /** What the writer needs from the provider. */
    record Context(MinecraftServer server, Supplier<Map<String, UUID>> registrations, Teams teams) {}

    /** An action that cannot run; carries the answer. */
    private static final class Refused extends RuntimeException {
        final transient ActionResult result;

        Refused(Status status, String message) {
            super(message, null, false, false);
            this.result = ActionResult.error(status, message);
        }
    }

    private Ae2Writer() {}

    /** Runs one action body, turning refusals into results. */
    static ActionResult run(Supplier<ActionResult> body) {
        try {
            return body.get();
        } catch (Refused r) {
            return r.result;
        }
    }

    // --- crafting CPUs ---------------------------------------------------------------------

    static ActionResult cancel(Context ctx, Actor actor, String gridId, String cpuId) {
        return run(() -> {
            IGrid grid = grid(ctx, actor, gridId);
            ICraftingCPU cpu = cpu(ctx, grid, cpuId);
            var status = cpu.getJobStatus();
            if (!cpu.isBusy() || status == null) throw new Refused(Status.INVALID, "This CPU has no job.");
            String before = status.crafting() == null ? "" : Ae2Reader.StackCache.id(status.crafting().what());
            cpu.cancelJob();
            return ActionResult.ok(List.of(new Change(Ae2Mapping.cpu(cpuId), "job", before, "")));
        });
    }

    static ActionResult suspend(Context ctx, Actor actor, String gridId, CpuSuspend edit) {
        return run(() -> {
            if (!Ae2Compat.canSuspend()) throw new Refused(Status.INVALID, "This AE2 version cannot suspend jobs.");
            IGrid grid = grid(ctx, actor, gridId);
            CraftingCPUCluster cluster = cluster(cpu(ctx, grid, edit.cpu()));
            if (!cluster.isBusy()) throw new Refused(Status.INVALID, "This CPU has no job.");
            boolean before = Boolean.TRUE.equals(Ae2Compat.suspended(cluster.craftingLogic));
            if (before == edit.suspended()) return ActionResult.ok(List.of());
            Ae2Compat.setSuspended(cluster.craftingLogic, edit.suspended());
            cluster.markDirty();
            boolean after = Boolean.TRUE.equals(Ae2Compat.suspended(cluster.craftingLogic));
            return ActionResult.ok(List.of(new Change(Ae2Mapping.cpu(edit.cpu()), "suspended",
                    Boolean.toString(before), Boolean.toString(after))));
        });
    }

    static ActionResult editCpu(Context ctx, Actor actor, String gridId, CpuEdit edit) {
        return run(() -> {
            IGrid grid = grid(ctx, actor, gridId);
            CraftingCPUCluster cluster = cluster(cpu(ctx, grid, edit.cpu()));
            var changes = new ArrayList<Change>();
            var ref = Ae2Mapping.cpu(edit.cpu());
            if (edit.name() != null) {
                if (!Ae2Compat.canName()) throw new Refused(Status.INVALID, "This AE2 version cannot rename CPUs.");
                String before = text(cluster.getName());
                if (!edit.name().equals(before)) {
                    nameCluster(cluster, edit.name());
                    changes.add(new Change(ref, "name", before, text(cluster.getName())));
                }
            }
            if (edit.mode() != null) {
                CpuSelectionMode mode = CpuSelectionMode.valueOf(edit.mode().name());
                CpuSelectionMode before = cluster.getSelectionMode();
                if (mode != before) {
                    cluster.getConfigManager().putSetting(Settings.CPU_SELECTION_MODE, mode);
                    cluster.markDirty();
                    changes.add(new Change(ref, "mode", lower(before.name()), lower(cluster.getSelectionMode().name())));
                }
            }
            return ActionResult.ok(changes);
        });
    }

    /**
     * AE2 names a CPU after the custom names of all its blocks, joined. The new name goes on one
     * block, the others lose theirs, so the CPU shows exactly that name, also after breaking and
     * rebuilding parts of it.
     */
    private static void nameCluster(CraftingCPUCluster cluster, String name) {
        Component component = name.isEmpty() ? null : Component.literal(name);
        boolean first = true;
        for (var it = cluster.getBlockEntities(); it.hasNext(); ) {
            CraftingBlockEntity block = it.next();
            Component wanted = first ? component : null;
            first = false;
            if (Objects.equals(block.getCustomName(), wanted)) continue;
            Ae2Compat.setName(block, wanted);
            block.saveChanges();
        }
        cluster.updateName();
    }

    // --- drives ----------------------------------------------------------------------------

    static ActionResult editDrive(Context ctx, Actor actor, String gridId, DriveEdit edit) {
        return run(() -> {
            IGrid grid = grid(ctx, actor, gridId);
            BlockEntity be = blockEntity(ctx, edit.drive());
            if (!(be instanceof IChestOrDrive) || !(be instanceof IPriorityHost host)) {
                throw new Refused(Status.NOT_FOUND, "There is no drive at " + edit.drive() + ".");
            }
            if (gridOf(be) != grid) throw new Refused(Status.INVALID, "This drive is not part of the network.");
            int before = host.getPriority();
            if (before == edit.priority()) return ActionResult.ok(List.of());
            host.setPriority(edit.priority());
            return ActionResult.ok(List.of(new Change(Ae2Mapping.drive(edit.drive()), "priority",
                    Integer.toString(before), Integer.toString(host.getPriority()))));
        });
    }

    // --- pattern providers -----------------------------------------------------------------

    static ActionResult editProvider(Context ctx, Actor actor, String gridId, ProviderEdit edit) {
        return run(() -> {
            IGrid grid = grid(ctx, actor, gridId);
            PatternProviderLogicHost host = provider(ctx, grid, edit.provider());
            Item icon = null;
            if (edit.icon() != null && !edit.icon().isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(edit.icon());
                icon = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
                if (icon == null || icon == Items.AIR) throw new Refused(Status.INVALID, "Unknown item: " + edit.icon());
            }
            if (edit.name() != null && !Ae2Compat.canName()) {
                throw new Refused(Status.INVALID, "This AE2 version cannot rename pattern providers.");
            }
            var ref = Ae2Mapping.provider(edit.provider());
            var changes = new ArrayList<Change>();
            IConfigManager config = host.getLogic().getConfigManager();

            if (edit.name() != null) {
                Component before = host instanceof Nameable named ? named.getCustomName() : null;
                if (!edit.name().equals(text(before))) {
                    Component name = edit.name().isEmpty() ? null : Component.literal(edit.name());
                    if (host instanceof AEBaseBlockEntity block) {
                        Ae2Compat.setName(block, name);
                        block.saveChanges();
                        block.markForUpdate();
                    } else if (host instanceof AEBasePart part) {
                        Ae2Compat.setName(part, name);
                        part.getHost().markForSave();
                        part.getHost().markForUpdate();
                    } else {
                        throw new Refused(Status.INVALID, "This pattern provider cannot be renamed.");
                    }
                    Component after = host instanceof Nameable named ? named.getCustomName() : null;
                    changes.add(new Change(ref, "name", text(before), text(after)));
                }
            }
            if (edit.priority() != null && edit.priority() != host.getPriority()) {
                int before = host.getPriority();
                host.setPriority(edit.priority());
                changes.add(new Change(ref, "priority", Integer.toString(before), Integer.toString(host.getPriority())));
            }
            if (edit.blocking() != null) {
                setting(config, Settings.BLOCKING_MODE, edit.blocking() ? YesNo.YES : YesNo.NO, ref, "blocking", changes);
            }
            if (edit.visible() != null) {
                setting(config, Settings.PATTERN_ACCESS_TERMINAL, edit.visible() ? YesNo.YES : YesNo.NO, ref, "visible",
                        changes);
            }
            if (edit.lock() != null) {
                setting(config, Settings.LOCK_CRAFTING_MODE, LockCraftingMode.valueOf(edit.lock().name()), ref, "lock",
                        changes);
            }
            if (edit.icon() != null) {
                String before = ProviderIcons.id(host);
                ProviderIcons.set(host, icon);
                String after = ProviderIcons.id(host);
                if (!Objects.equals(before, after)) changes.add(new Change(ref, "icon", before, after));
            }
            return ActionResult.ok(changes);
        });
    }

    /** A setting through AE2's config manager, which saves the provider like its GUI buttons do. */
    private static <T extends Enum<T>> void setting(IConfigManager config, Setting<T> setting, T value, ResourceRef ref,
            String field, List<Change> changes) {
        T before = config.getSetting(setting);
        if (before == value) return;
        config.putSetting(setting, value);
        changes.add(new Change(ref, field, settingText(before), settingText(config.getSetting(setting))));
    }

    private static String settingText(Enum<?> value) {
        if (value == YesNo.YES) return "true";
        if (value == YesNo.NO) return "false";
        return lower(value.name());
    }

    /** A provider of the grid: {@code dimension@x,y,z} for a block, {@code .../<side>} for a part. */
    private static PatternProviderLogicHost provider(Context ctx, IGrid grid, String id) {
        int slash = id.indexOf('/', id.indexOf('@'));
        String position = slash < 0 ? id : id.substring(0, slash);
        BlockEntity be = blockEntity(ctx, position);
        PatternProviderLogicHost host = null;
        if (slash < 0) {
            if (be instanceof PatternProviderLogicHost block) host = block;
        } else {
            Direction side = Direction.byName(id.substring(slash + 1));
            if (side != null && be instanceof IPartHost parts && parts.getPart(side) instanceof PatternProviderLogicHost part) {
                host = part;
            }
        }
        if (host == null) throw new Refused(Status.NOT_FOUND, "There is no pattern provider at " + id + ".");
        IGridNode node = host instanceof IActionHost actionHost ? actionHost.getActionableNode() : null;
        if (node == null || node.getGrid() != grid) {
            throw new Refused(Status.INVALID, "This pattern provider is not part of the network.");
        }
        return host;
    }

    // --- lookup ----------------------------------------------------------------------------

    /**
     * The live grid the id names, after checking the actor may change it. The id must still
     * identify this grid: a removed controller makes it another grid.
     */
    private static IGrid grid(Context ctx, Actor actor, String gridId) {
        Located at = locate(ctx.server(), gridId);
        IGridNode node = Ae2Reader.nodeAt(at.level(), at.pos());
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) throw new Refused(Status.NOT_FOUND, "The network no longer exists.");
        Ae2Reader.Identity identity = Ae2Reader.identify(ctx.server(), grid, ctx.registrations().get());
        if (identity == null || !identity.id().equals(gridId)) {
            throw new Refused(Status.NOT_FOUND, "The network no longer exists.");
        }
        if (!actor.operator() && !mayAct(ctx.teams(), identity.owner(), actor.player())) {
            throw new Refused(Status.DENIED, "Only the network's owner, their party and operators may change it.");
        }
        return grid;
    }

    static boolean mayAct(Teams teams, UUID owner, UUID player) {
        if (owner == null) return false;
        return owner.equals(player) || teams.teammates(owner).contains(player);
    }

    /** A CPU of the grid: by its lowest corner, or {@code <grid>#<index>} for CPUs without blocks. */
    private static ICraftingCPU cpu(Context ctx, IGrid grid, String cpuId) {
        int hash = cpuId.indexOf('#');
        if (hash >= 0) {
            int index;
            try {
                index = Integer.parseInt(cpuId.substring(hash + 1));
            } catch (NumberFormatException e) {
                throw new Refused(Status.INVALID, "Unknown CPU: " + cpuId);
            }
            int i = 0;
            for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
                if (i++ == index) return cpu;
            }
            throw new Refused(Status.NOT_FOUND, "The CPU no longer exists.");
        }
        BlockEntity be = blockEntity(ctx, cpuId);
        if (!(be instanceof CraftingBlockEntity block) || block.getCluster() == null) {
            throw new Refused(Status.NOT_FOUND, "The CPU no longer exists.");
        }
        CraftingCPUCluster cluster = block.getCluster();
        if (cluster.getGrid() != grid) throw new Refused(Status.INVALID, "This CPU is not part of the network.");
        return cluster;
    }

    private static CraftingCPUCluster cluster(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) return cluster;
        throw new Refused(Status.INVALID, "This CPU cannot be changed from the dashboard.");
    }

    private static IGrid gridOf(BlockEntity be) {
        if (be.getLevel() == null) return null;
        IGridNode node = Ae2Reader.nodeAt(be.getLevel(), be.getBlockPos());
        return node == null ? null : node.getGrid();
    }

    private record Located(ServerLevel level, BlockPos pos) {}

    /** {@code dimension@x,y,z} to a loaded position; chunks are never loaded for Overseer. */
    private static Located locate(MinecraftServer server, String id) {
        int at = id.lastIndexOf('@');
        ResourceLocation dimension = at < 0 ? null : ResourceLocation.tryParse(id.substring(0, at));
        String[] xyz = at < 0 ? new String[0] : id.substring(at + 1).split(",");
        if (dimension == null || xyz.length != 3) throw new Refused(Status.INVALID, "Unknown position: " + id);
        BlockPos pos;
        try {
            pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        } catch (NumberFormatException e) {
            throw new Refused(Status.INVALID, "Unknown position: " + id);
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) throw new Refused(Status.NOT_FOUND, "Unknown dimension: " + dimension);
        if (!level.hasChunkAt(pos)) {
            throw new Refused(Status.UNAVAILABLE, "That part of the world is not loaded. AE2 can only change loaded blocks.");
        }
        return new Located(level, pos);
    }

    private static BlockEntity blockEntity(Context ctx, String id) {
        Located at = locate(ctx.server(), id);
        return at.level().getBlockEntity(at.pos());
    }

    private static String text(Component component) {
        return component == null ? "" : component.getString();
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
