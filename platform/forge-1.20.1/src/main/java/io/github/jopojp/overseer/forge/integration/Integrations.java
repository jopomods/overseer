package io.github.jopojp.overseer.forge.integration;

import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.forge.integration.server.ServerProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the provider list for this server. Mod integrations are added only when their
 * mod is loaded, and their classes are touched only behind that check (AGENTS.md rule 3):
 * the factory lambdas below are not linked until invoked.
 */
public final class Integrations {
    private static final Logger LOG = LoggerFactory.getLogger(Integrations.class);

    private Integrations() {}

    public static List<DataProvider> providers(MinecraftServer server) {
        var providers = new ArrayList<DataProvider>();
        providers.add(new ServerProvider(server));
        optional(providers, "fluxnetworks", () -> new io.github.jopojp.overseer.forge.integration.flux.FluxProvider());
        return providers;
    }

    private static void optional(List<DataProvider> providers, String modId, Supplier<DataProvider> factory) {
        if (!ModList.get().isLoaded(modId)) return;
        try {
            providers.add(factory.get());
        } catch (LinkageError | RuntimeException e) {
            // An incompatible version of the mod must not take the dashboard down.
            LOG.error("Overseer integration for '{}' failed to load; skipping it", modId, e);
        }
    }
}
