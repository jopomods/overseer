package io.github.jopojp.overseer.forge.integration.profiler;

import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.storage.ProfilerStore;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.timings.ForgeTimings;
import net.minecraftforge.server.timings.TimeTracker;

/**
 * Tick profiler (ADR-0014): Forge's own per-object timers ({@link TimeTracker}, the ones behind
 * {@code /forge track}) for block entities and entities, read every {@code interval} seconds while
 * an admin has the profiler on. Each read takes the last up to 100 ticks per object, then the
 * timers restart, so every measurement covers only its own interval. Admins only.
 *
 * <p>The timers cost a little time per ticking object while on; off, Forge skips them entirely.
 */
public final class ProfilerProvider implements DataProvider {
    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(ProfilerMapping.PROVIDER,
            "Tick profiler", Set.of(Capability.METRICS, Capability.ACCESS_CONTROL), ProfilerMapping.METRICS, 20,
            TimeUnit.MILLISECONDS.toNanos(5));
    /** Forge stops a timer after this many seconds; we re-arm it on every collection anyway. */
    private static final int ARM_SECONDS = 3600;

    private final Supplier<ProfilerStore.Settings> settings;
    private boolean running;
    private long collections;
    private ProfilerMapping.Measurement last = ProfilerMapping.Measurement.EMPTY;

    public ProfilerProvider(Supplier<ProfilerStore.Settings> settings) {
        this.settings = settings;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        ProfilerStore.Settings s = settings.get();
        boolean active = s != null && s.active(Instant.now());
        boolean fresh = false;
        if (!active) {
            if (running) {
                TimeTracker.BLOCK_ENTITY_UPDATE.reset();
                TimeTracker.ENTITY_UPDATE.reset();
                running = false;
            }
        } else {
            if (!running) {
                restart();
                running = true;
                collections = 0;
            }
            // Forge disables a timer once its duration ran out; keep it armed.
            TimeTracker.BLOCK_ENTITY_UPDATE.enable(ARM_SECONDS);
            TimeTracker.ENTITY_UPDATE.enable(ARM_SECONDS);
            if (++collections % s.intervalSeconds() == 0) {
                last = ProfilerMapping.measure(read(), ProfilerProvider::modName);
                fresh = true;
                restart();
            }
        }
        // The last result stays visible after the profiler stops, until the next start.
        return ProfilerMapping.snapshot(at, last, fresh, Map.of());
    }

    private static void restart() {
        TimeTracker.BLOCK_ENTITY_UPDATE.reset();
        TimeTracker.ENTITY_UPDATE.reset();
        TimeTracker.BLOCK_ENTITY_UPDATE.enable(ARM_SECONDS);
        TimeTracker.ENTITY_UPDATE.enable(ARM_SECONDS);
    }

    private static List<ProfilerMapping.Timing> read() {
        var result = new ArrayList<ProfilerMapping.Timing>();
        for (ForgeTimings<BlockEntity> t : TimeTracker.BLOCK_ENTITY_UPDATE.getTimingData()) {
            BlockEntity be = t.getObject().get();
            if (be == null || be.isRemoved() || be.getLevel() == null) continue;
            var block = be.getBlockState().getBlock();
            var pos = be.getBlockPos();
            result.add(new ProfilerMapping.Timing("block", String.valueOf(ForgeRegistries.BLOCKS.getKey(block)),
                    block.getName().getString(), where(be.getLevel(), pos.getX(), pos.getY(), pos.getZ()),
                    t.getAverageTimings()));
        }
        for (ForgeTimings<Entity> t : TimeTracker.ENTITY_UPDATE.getTimingData()) {
            Entity e = t.getObject().get();
            if (e == null || e.isRemoved()) continue;
            var type = e.getType();
            result.add(new ProfilerMapping.Timing("entity", String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(type)),
                    type.getDescription().getString(), where(e.level(), e.getBlockX(), e.getBlockY(), e.getBlockZ()),
                    t.getAverageTimings()));
        }
        return result;
    }

    private static String modName(String namespace) {
        return net.minecraftforge.fml.ModList.get().getModContainerById(namespace)
                .map(c -> c.getModInfo().getDisplayName()).orElse(namespace);
    }

    private static String where(Level level, int x, int y, int z) {
        return level.dimension().location() + "@" + x + "," + y + "," + z;
    }

    /** Nobody but admins (core lets them through before asking). */
    @Override
    public AccessPolicy access() {
        return (player, resource) -> false;
    }
}
