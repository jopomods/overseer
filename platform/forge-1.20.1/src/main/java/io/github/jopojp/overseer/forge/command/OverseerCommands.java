package io.github.jopojp.overseer.forge.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.jopojp.overseer.core.OverseerRuntime;
import io.github.jopojp.overseer.core.auth.AuthService;
import io.github.jopojp.overseer.core.auth.LinkCodes;
import io.github.jopojp.overseer.core.auth.PlayerRef;
import io.github.jopojp.overseer.forge.OverseerConfig;
import io.github.jopojp.overseer.forge.ServerLifecycle;
import java.util.Collection;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /ov} commands (ADR-0007). Every command also works from the server console,
 * which is always super-admin. Database work runs off the server thread; replies are
 * sent back on it.
 *
 * <ul>
 *   <li>{@code /ov link <code>}: confirm a dashboard login (players)</li>
 *   <li>{@code /ov status}: dashboard address (everyone)</li>
 *   <li>{@code /ov login <player>}: one-time login link, the recovery path (level 4 / console)</li>
 *   <li>{@code /ov admin add|remove <player>}: dashboard admins (ops)</li>
 *   <li>{@code /ov sessions revoke <player>}: log a player out everywhere (ops)</li>
 * </ul>
 */
public final class OverseerCommands {
    private static final Logger LOG = LoggerFactory.getLogger(OverseerCommands.class);

    private static final SimpleCommandExceptionType ONE_PLAYER =
            new SimpleCommandExceptionType(Component.literal("Select exactly one player."));

    private OverseerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ov")
                .then(Commands.literal("link")
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(OverseerCommands::link)))
                .then(Commands.literal("status")
                        .executes(OverseerCommands::status))
                .then(Commands.literal("login")
                        .requires(s -> s.hasPermission(4))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(OverseerCommands::login)))
                .then(Commands.literal("admin")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(ctx -> admin(ctx, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(ctx -> admin(ctx, false)))))
                .then(Commands.literal("sessions")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(OverseerCommands::revokeSessions)))));
    }

    private static int link(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String code = StringArgumentType.getString(ctx, "code");
        var ref = new PlayerRef(player.getUUID(), player.getGameProfile().getName());
        return run(ctx, auth -> {
            LinkCodes.ConfirmResult result = auth.confirmLink(code, ref);
            return switch (result) {
                case CONFIRMED -> ok("Dashboard login confirmed. Your browser continues automatically.");
                case ALREADY_CONFIRMED -> error("This code was already used.");
                case UNKNOWN_OR_EXPIRED -> error("Unknown or expired code. Request a new one in the browser.");
            };
        });
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        var runtime = ServerLifecycle.runtime();
        if (runtime.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Overseer dashboard is not running. Check the server log."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Overseer dashboard: ").append(link(OverseerConfig.publicUrl())), false);
        return 1;
    }

    private static int login(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GameProfile profile = single(ctx);
        var ref = new PlayerRef(profile.getId(), profile.getName());
        return run(ctx, auth -> {
            String token = auth.issueConsoleLogin(ref);
            String url = OverseerConfig.publicUrl() + "/api/v1/auth/login?token=" + token;
            return ok(Component.literal("One-time login for " + ref.name() + " (valid 10 minutes): ").append(link(url)));
        });
    }

    private static int admin(CommandContext<CommandSourceStack> ctx, boolean grant) throws CommandSyntaxException {
        GameProfile profile = single(ctx);
        var ref = new PlayerRef(profile.getId(), profile.getName());
        String by = ctx.getSource().getTextName();
        return run(ctx, auth -> {
            boolean changed = grant ? auth.grantAdmin(ref, by) : auth.revokeAdmin(ref.uuid());
            if (grant) return ok(changed ? ref.name() + " is now a dashboard admin." : ref.name() + " already is a dashboard admin.");
            return ok(changed ? ref.name() + " is no longer a dashboard admin." : ref.name() + " was not a dashboard admin.");
        });
    }

    private static int revokeSessions(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GameProfile profile = single(ctx);
        return run(ctx, auth -> ok("Logged out " + profile.getName() + " from "
                + auth.revokeAllSessions(profile.getId()) + " session(s)."));
    }

    // --- helpers -------------------------------------------------------------------------

    private record Reply(Component message, boolean success) {}

    @FunctionalInterface
    private interface AuthAction {
        Reply run(AuthService auth) throws Exception;
    }

    /**
     * Runs {@code action} and replies immediately. Synchronous on purpose: replies sent
     * later are lost for RCON and command blocks. These commands are rare and do at most a
     * couple of small SQLite writes (milliseconds), an accepted exception to "no IO on the
     * server thread".
     */
    private static int run(CommandContext<CommandSourceStack> ctx, AuthAction action) {
        CommandSourceStack source = ctx.getSource();
        OverseerRuntime runtime = ServerLifecycle.runtime().orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Overseer dashboard is not running."));
            return 0;
        }
        Reply reply;
        try {
            reply = action.run(runtime.auth());
        } catch (Exception e) {
            LOG.error("Overseer command failed", e);
            reply = error("Overseer could not complete the command. See the server log.");
        }
        if (reply.success()) {
            Reply success = reply;
            source.sendSuccess(success::message, false);
            return 1;
        }
        source.sendFailure(reply.message());
        return 0;
    }

    private static GameProfile single(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            throw ONE_PLAYER.create();
        }
        return profiles.iterator().next();
    }

    private static Reply ok(String text) {
        return new Reply(Component.literal(text).withStyle(ChatFormatting.GREEN), true);
    }

    private static Reply ok(Component message) {
        return new Reply(message, true);
    }

    private static Reply error(String text) {
        return new Reply(Component.literal(text), false);
    }

    private static MutableComponent link(String url) {
        return Component.literal(url).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open in browser"))));
    }

}
