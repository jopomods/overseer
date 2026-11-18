package io.github.jopojp.overseer.forge.integration;

import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.forge.ServerLifecycle;
import io.github.jopojp.overseer.forge.integration.server.ServerProvider;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
        Teams teams = Teams.detect();
        providers.add(new ServerProvider(server));
        optional(providers, "fluxnetworks", () -> new io.github.jopojp.overseer.forge.integration.flux.FluxProvider());
        optional(providers, "ae2", () -> new io.github.jopojp.overseer.forge.integration.ae2.Ae2Provider(server, teams,
                () -> registrations("ae2"), () -> watches("ae2"))
                .placerAccess(io.github.jopojp.overseer.forge.OverseerConfig::ae2PlacerAccess));
        return providers;
    }

    /** Registered resources of a provider as id to owner; empty while Overseer is not running. */
    private static Map<String, UUID> registrations(String provider) {
        return ServerLifecycle.runtime()
                .map(r -> r.registrations().list(provider).stream()
                        .collect(Collectors.toMap(reg -> reg.resource().id(), reg -> reg.owner(), (a, b) -> a)))
                .orElse(Map.of());
    }

    /** Watched stack keys per resource id; empty while Overseer is not running. */
    private static Map<String, java.util.Set<String>> watches(String provider) {
        return ServerLifecycle.runtime().map(r -> r.watches().keysByResource(provider)).orElse(Map.of());
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
