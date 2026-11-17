package io.github.jopojp.overseer.forge;

import com.mojang.authlib.GameProfile;
import io.github.jopojp.overseer.core.auth.OperatorCheck;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers "is this player an operator?" for HTTP threads (ADR-0008). The op list belongs
 * to the server thread, so lookups run there via {@link MinecraftServer#submit} and are
 * cached for a few seconds.
 */
final class ForgeOperatorCheck implements OperatorCheck {
    private static final Logger LOG = LoggerFactory.getLogger(ForgeOperatorCheck.class);
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private record Cached(boolean operator, long expiresNanos) {}

    private final MinecraftServer server;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    ForgeOperatorCheck(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isOperator(UUID player) {
        long now = System.nanoTime();
        Cached cached = cache.get(player);
        if (cached != null && now < cached.expiresNanos()) return cached.operator();
        boolean operator;
        try {
            operator = server.isSameThread() ? lookup(player) : server.submit(() -> lookup(player)).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.debug("Overseer op lookup for {} failed; treating as non-operator", player, e);
            return false;
        }
        cache.put(player, new Cached(operator, now + TTL_NANOS));
        return operator;
    }

    /** Server thread only. Singleplayer: the world owner counts as operator. */
    private boolean lookup(UUID player) {
        GameProfile profile = server.getProfileCache() == null ? null
                : server.getProfileCache().get(player).orElse(null);
        if (profile == null) profile = new GameProfile(player, null);
        if (server.getProfilePermissions(profile) >= 2) return true;
        return profile.getName() != null && server.isSingleplayerOwner(profile);
    }
}
