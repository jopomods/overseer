package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.blockentity.networking.ControllerBlockEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.jopojp.overseer.core.OverseerRuntime;
import io.github.jopojp.overseer.core.api.ApiRoutes;
import io.github.jopojp.overseer.forge.ServerLifecycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /ov ae2 register <name>}: a player registers the ME network they are looking at when it
 * has no controller (ADR-0011). Its id becomes the position of that block; the player becomes
 * the owner. Registered only when AE2 is loaded.
 */
public final class Ae2Commands {
    private static final Logger LOG = LoggerFactory.getLogger(Ae2Commands.class);
    private static final double REACH = 6.0;

    private Ae2Commands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("ae2")
                .then(Commands.literal("register")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(Ae2Commands::register)));
    }

    private static int register(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name").strip();
        if (name.isEmpty() || name.length() > ApiRoutes.MAX_OVERRIDE_LENGTH) {
            return fail(source, "The name must have 1 to " + ApiRoutes.MAX_OVERRIDE_LENGTH + " characters.");
        }
        OverseerRuntime runtime = ServerLifecycle.runtime().orElse(null);
        if (runtime == null) return fail(source, "Overseer dashboard is not running.");

        HitResult hit = player.pick(REACH, 0f, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return fail(source, "Look at a block of the ME network (cable, drive, interface, ...).");
        }
        BlockPos pos = block.getBlockPos();
        IGridNode node = Ae2Reader.nodeAt(player.level(), pos);
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) return fail(source, "This block is not part of an ME network.");
        if (!grid.getMachines(ControllerBlockEntity.class).isEmpty()) {
            return fail(source, "This network has a controller; it appears in the dashboard by itself.");
        }

        String dimension = player.level().dimension().location().toString();
        for (var existing : runtime.registrations().list(Ae2Mapping.PROVIDER)) {
            if (sameGrid(player, existing.resource().id(), grid)) {
                return fail(source, "This network is already registered.");
            }
        }
        try {
            runtime.register(Ae2Mapping.grid(dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()),
                    player.getUUID(), name);
        } catch (Exception e) {
            LOG.error("Overseer could not register the ME network", e);
            return fail(source, "Overseer could not register the network. See the server log.");
        }
        source.sendSuccess(() -> Component.literal("Registered \"" + name + "\". It shows up in the dashboard within a second.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Whether a registered id ({@code dimension@x,y,z}) points into this grid. Loaded chunks only. */
    private static boolean sameGrid(ServerPlayer player, String id, IGrid grid) {
        int at = id.lastIndexOf('@');
        if (at < 0 || !id.substring(0, at).equals(player.level().dimension().location().toString())) return false;
        String[] xyz = id.substring(at + 1).split(",");
        try {
            var pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            if (!player.level().hasChunkAt(pos)) return false;
            IGridNode node = Ae2Reader.nodeAt(player.level(), pos);
            return node != null && node.getGrid() == grid;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
