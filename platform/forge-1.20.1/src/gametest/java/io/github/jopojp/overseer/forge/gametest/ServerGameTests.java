package io.github.jopojp.overseer.forge.gametest;

import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.Integrations;
import io.github.jopojp.overseer.forge.integration.server.ServerProvider;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Tests that need no optional mod. They also run with {@code -PbareMods=true}, where Flux
 * Networks and AE2 are absent (AGENTS.md rule 3).
 */
@GameTestHolder(Overseer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ServerGameTests {
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private ServerGameTests() {}

    @GameTest(template = "empty")
    public static void serverProviderReportsTpsAndDimensions(GameTestHelper helper) {
        var snapshot = new ServerProvider(helper.getLevel().getServer()).collect(AT);
        helper.assertTrue(snapshot.resources().containsKey(new ResourceRef("server", "server", "main")), "server");
        helper.assertTrue(snapshot.resources().containsKey(
                new ResourceRef("server", "dimension", "minecraft:overworld")), "overworld");
        helper.assertTrue(snapshot.samples().stream().anyMatch(s -> s.metric().equals("tps")), "tps sample");
        helper.succeed();
    }

    /** Integrations exist exactly for the loaded mods, and every one of them collects. */
    @GameTest(template = "empty")
    public static void integrationsFollowLoadedMods(GameTestHelper helper) {
        boolean bare = Boolean.getBoolean("overseer.bareMods");
        boolean flux = ModList.get().isLoaded("fluxnetworks");
        boolean ae2 = ModList.get().isLoaded("ae2");
        helper.assertTrue(flux == !bare && ae2 == !bare,
                "expected optional mods " + (bare ? "absent" : "present") + ": flux=" + flux + ", ae2=" + ae2);

        var providers = Integrations.providers(helper.getLevel().getServer());
        Set<String> ids = providers.stream().map(p -> p.descriptor().id()).collect(Collectors.toSet());
        Set<String> expected = bare ? Set.of("server") : Set.of("server", "flux", "ae2");
        helper.assertTrue(ids.equals(expected), "providers " + ids + ", expected " + expected);
        for (DataProvider provider : providers) provider.collect(AT);
        helper.succeed();
    }
}
