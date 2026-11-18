package io.github.jopojp.overseer.forge.gametest;

import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.ProfilerStore;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.profiler.ProfilerMapping;
import io.github.jopojp.overseer.forge.integration.profiler.ProfilerProvider;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.server.timings.TimeTracker;

/** The tick profiler against real ticking block entities. */
@GameTestHolder(Overseer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProfilerGameTests {
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private ProfilerGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void measuresTickingBlockEntitiesWhileOn(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.HOPPER);
        var settings = new AtomicReference<>(new ProfilerStore.Settings(ProfilerStore.Mode.CONTINUOUS, 0, 5));
        var provider = new ProfilerProvider(settings::get);
        helper.runAfterDelay(1, () -> provider.collect(AT)); // arms Forge's timers
        helper.runAfterDelay(120, () -> {
            for (int i = 0; i < 3; i++) provider.collect(AT);
            var snapshot = provider.collect(AT); // 5th collection: measures
            // Other tests' structures tick too, so the furnace may land in "other": any block type
            // proves that block entities are timed (that needs the mixin, ADR-0014).
            boolean blocks = snapshot.resources().keySet().stream()
                    .anyMatch(r -> r.type().equals("type") && r.id().startsWith("block:"));
            helper.assertTrue(blocks, "block entities measured; got " + snapshot.resources().keySet());
            helper.assertTrue(snapshot.samples().stream().anyMatch(s -> s.resource().equals(ProfilerMapping.TOTAL)
                    && s.metric().equals("tick_us") && s.value() > 0), "total time");
            helper.assertTrue(!snapshot.details().getOrDefault(ProfilerMapping.TOTAL, java.util.List.of()).isEmpty(),
                    "top list");
            settings.set(ProfilerStore.Settings.OFF);
            var off = provider.collect(AT);
            helper.assertTrue(off.samples().isEmpty(), "no new samples once off");
            helper.assertTrue(off.resources().keySet().equals(snapshot.resources().keySet()), "the last result stays visible");
            helper.assertTrue(TimeTracker.BLOCK_ENTITY_UPDATE.getTimingData().isEmpty(), "timers stopped and cleared");
            helper.succeed();
        });
    }
}
