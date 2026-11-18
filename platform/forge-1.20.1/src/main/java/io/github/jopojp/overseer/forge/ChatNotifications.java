package io.github.jopojp.overseer.forge;

import io.github.jopojp.overseer.core.storage.NotificationStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers Overseer's in-game messages (e.g. access requests) to online players: checked
 * every 2 seconds from the server tick, so players who log in get them right away. The check
 * is a memory lookup; the database is touched only when a player has messages.
 */
final class ChatNotifications {
    private static final Logger LOG = LoggerFactory.getLogger(ChatNotifications.class);
    private static final int INTERVAL_TICKS = 40;

    private int ticks;

    /** Server thread. */
    void tick(MinecraftServer server, NotificationStore store) {
        if (++ticks < INTERVAL_TICKS) return;
        ticks = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!store.hasPending(player.getUUID())) continue;
            try {
                for (String message : store.take(player.getUUID())) player.sendSystemMessage(format(message));
            } catch (Exception e) {
                LOG.warn("Overseer could not deliver messages to {}", player.getGameProfile().getName(), e);
            }
        }
    }

    private static Component format(String message) {
        var text = Component.literal(message).withStyle(ChatFormatting.GOLD);
        String url = OverseerConfig.publicUrl();
        if (url == null || url.isBlank()) return text;
        return text.append(Component.literal(" [" + url + "]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
    }
}
