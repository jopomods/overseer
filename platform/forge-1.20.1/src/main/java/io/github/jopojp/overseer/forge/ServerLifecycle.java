package io.github.jopojp.overseer.forge;

import io.github.jopojp.overseer.core.OverseerRuntime;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.forge.command.OverseerCommands;
import io.github.jopojp.overseer.forge.integration.Integrations;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts Overseer with the server, drives it from the server tick and stops it on shutdown.
 * A failure to start (e.g. port in use) is logged and the server keeps running without
 * the dashboard.
 */
public final class ServerLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ServerLifecycle.class);

    private static volatile OverseerRuntime runtime;
    private final String version;

    ServerLifecycle(String version) {
        this.version = version;
    }

    /** The running Overseer, if the dashboard is up. */
    public static Optional<OverseerRuntime> runtime() {
        return Optional.ofNullable(runtime);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!OverseerConfig.ENABLED.get()) {
            LOG.info("Overseer web dashboard is disabled in overseer-server.toml");
            return;
        }
        MinecraftServer server = event.getServer();
        Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("overseer");
        List<DataProvider> providers = Integrations.providers(server);
        var config = OverseerRuntime.Config.defaults(dataDir, version);
        config = new OverseerRuntime.Config(dataDir, OverseerConfig.http(), config.storage(),
                config.sessionIdleTimeout(), config.queueCapacity(), version, config.webRoot());
        try {
            runtime = OverseerRuntime.start(config, providers, new ForgeOperatorCheck(server));
            LOG.info("Overseer dashboard: {} ({} providers: {})", OverseerConfig.publicUrl(), providers.size(),
                    providers.stream().map(p -> p.descriptor().id()).toList());
        } catch (Exception e) {
            LOG.error("Overseer failed to start; the server continues without the dashboard", e);
            runtime = null;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        OverseerRuntime current = runtime;
        if (current != null) current.tick();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        OverseerRuntime current = runtime;
        runtime = null;
        if (current != null) current.close();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        OverseerCommands.register(event.getDispatcher());
    }
}
