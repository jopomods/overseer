package io.github.jopojp.overseer.forge.integration.server;

import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.MetricDescriptor;
import io.github.jopojp.overseer.core.provider.MetricKind;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Vanilla server data: TPS/MSPT, players, per-dimension load, JVM memory.
 *
 * <p>Resources and visibility (ADR-0008):
 * <ul>
 *   <li>{@code server/server/main}, {@code server/player/<uuid>}: every logged-in player;</li>
 *   <li>{@code server/dimension/<id>}, {@code server/jvm/main}: admins only.</li>
 * </ul>
 */
public final class ServerProvider implements DataProvider {
    public static final String ID = "server";

    static final MetricDescriptor TPS = new MetricDescriptor("tps", MetricKind.GAUGE, "1/s", 0.01);
    static final MetricDescriptor MSPT = new MetricDescriptor("mspt", MetricKind.GAUGE, "ms", 0.05);
    static final MetricDescriptor PLAYERS = new MetricDescriptor("players_online", MetricKind.GAUGE, "count", 0);
    static final MetricDescriptor PING = new MetricDescriptor("ping", MetricKind.GAUGE, "ms", 0.25);
    static final MetricDescriptor CHUNKS = new MetricDescriptor("loaded_chunks", MetricKind.GAUGE, "count", 0.02);
    static final MetricDescriptor ENTITIES = new MetricDescriptor("entities", MetricKind.GAUGE, "count", 0.02);
    static final MetricDescriptor DIM_MSPT = new MetricDescriptor("dimension_mspt", MetricKind.GAUGE, "ms", 0.05);
    static final MetricDescriptor MEMORY_USED = new MetricDescriptor("memory_used", MetricKind.GAUGE, "B", 0.05);
    static final MetricDescriptor MEMORY_MAX = new MetricDescriptor("memory_max", MetricKind.GAUGE, "B", 0);

    private static final ResourceRef SERVER = new ResourceRef(ID, "server", "main");
    private static final ResourceRef JVM = new ResourceRef(ID, "jvm", "main");
    private static final Set<String> PUBLIC_TYPES = Set.of("server", "player");

    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(ID, "Server",
            Set.of(Capability.METRICS, Capability.EVENTS, Capability.ACCESS_CONTROL),
            List.of(TPS, MSPT, PLAYERS, PING, CHUNKS, ENTITIES, DIM_MSPT, MEMORY_USED, MEMORY_MAX),
            20, TimeUnit.MILLISECONDS.toNanos(2));

    private final MinecraftServer server;

    public ServerProvider(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        Map<ResourceRef, Map<String, String>> resources = new HashMap<>();
        List<Sample> samples = new ArrayList<>();

        double mspt = server.getAverageTickTime();
        resources.put(SERVER, Map.of(
                "version", server.getServerVersion(),
                "motd", server.getMotd() == null ? "" : server.getMotd(),
                "maxPlayers", Integer.toString(server.getMaxPlayers())));
        samples.add(new Sample(SERVER, MSPT.key(), mspt));
        samples.add(new Sample(SERVER, TPS.key(), mspt <= 50 ? 20 : 1000 / mspt));
        samples.add(new Sample(SERVER, PLAYERS.key(), server.getPlayerCount()));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var ref = new ResourceRef(ID, "player", player.getUUID().toString());
            resources.put(ref, Map.of(
                    "name", player.getGameProfile().getName(),
                    "dimension", player.level().dimension().location().toString()));
            samples.add(new Sample(ref, PING.key(), player.latency));
        }

        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            var ref = new ResourceRef(ID, "dimension", dimension);
            resources.put(ref, Map.of("name", dimension));
            samples.add(new Sample(ref, CHUNKS.key(), level.getChunkSource().getLoadedChunksCount()));
            int entities = 0;
            for (var ignored : level.getAllEntities()) entities++;
            samples.add(new Sample(ref, ENTITIES.key(), entities));
            long[] times = server.getTickTime(level.dimension());
            if (times != null && times.length > 0) {
                long sum = 0;
                for (long t : times) sum += t;
                samples.add(new Sample(ref, DIM_MSPT.key(), sum / (double) times.length / 1_000_000.0));
            }
        }

        Runtime runtime = Runtime.getRuntime();
        resources.put(JVM, Map.of("java", System.getProperty("java.version", "")));
        samples.add(new Sample(JVM, MEMORY_USED.key(), runtime.totalMemory() - runtime.freeMemory()));
        samples.add(new Sample(JVM, MEMORY_MAX.key(), runtime.maxMemory()));

        return new ProviderSnapshot(ID, at, resources, samples);
    }

    @Override
    public AccessPolicy access() {
        return (player, resource) -> PUBLIC_TYPES.contains(resource.type());
    }
}
