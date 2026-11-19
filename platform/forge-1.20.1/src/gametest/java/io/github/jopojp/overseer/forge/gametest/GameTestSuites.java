package io.github.jopojp.overseer.forge.gametest;

import io.github.jopojp.overseer.core.Overseer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers the GameTest classes of one suite (ADR-0015). The suite comes from the system property
 * {@code overseer.gametestSuite}, set by Gradle's {@code -PgametestSuite}; Gradle loads the matching
 * optional mods. Classes are named, not referenced, so a test class of a mod that is not loaded is
 * never touched. Keep this table and the one in build.gradle.kts in sync.
 */
@Mod.EventBusSubscriber(modid = Overseer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GameTestSuites {
    private static final String PACKAGE = "io.github.jopojp.overseer.forge.gametest.";

    /** Suite -> optional mods loaded and GameTest classes run. */
    private record Suite(Set<String> mods, List<String> classes) {}

    private static final Map<String, Suite> SUITES = Map.of(
            "all", new Suite(Set.of("flux", "ae2", "teams"), List.of("ServerGameTests", "IconGameTests",
                    "ProfilerGameTests", "FluxGameTests", "Ae2GameTests", "Ae2ActionGameTests", "TeamGameTests", "Ae2TeamGameTests")),
            "bare", new Suite(Set.of(), List.of("ServerGameTests", "IconGameTests", "ProfilerGameTests")),
            "base", new Suite(Set.of(), List.of("ServerGameTests", "IconGameTests")),
            "profiler", new Suite(Set.of(), List.of("ProfilerGameTests")),
            "flux", new Suite(Set.of("flux"), List.of("ServerGameTests", "FluxGameTests")),
            "ae2", new Suite(Set.of("ae2"), List.of("ServerGameTests", "Ae2GameTests", "Ae2ActionGameTests", "IconGameTests")),
            "teams", new Suite(Set.of("teams"), List.of("ServerGameTests", "TeamGameTests")),
            "ae2-teams", new Suite(Set.of("ae2", "teams"), List.of("ServerGameTests", "Ae2TeamGameTests")));

    private GameTestSuites() {}

    /** Optional mods of a suite: flux, ae2, teams. */
    static Set<String> mods(String suite) {
        return SUITES.getOrDefault(suite, SUITES.get("all")).mods();
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        String name = System.getProperty("overseer.gametestSuite", "all");
        Suite suite = SUITES.get(name);
        if (suite == null) throw new IllegalStateException("Unknown GameTest suite " + name);
        for (String simpleName : suite.classes()) {
            try {
                event.register(Class.forName(PACKAGE + simpleName));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("GameTest class missing: " + simpleName, e);
            }
        }
    }
}
