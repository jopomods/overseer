package io.github.jopojp.overseer.forge.integration;

import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.forge.integration.server.ServerProvider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Builds the provider list for this server. Mod integrations are added only when their
 * mod is loaded, and their classes are touched only behind that check (AGENTS.md rule 3).
 */
public final class Integrations {
    private Integrations() {}

    public static List<DataProvider> providers(MinecraftServer server) {
        var providers = new ArrayList<DataProvider>();
        providers.add(new ServerProvider(server));
        return providers;
    }
}
