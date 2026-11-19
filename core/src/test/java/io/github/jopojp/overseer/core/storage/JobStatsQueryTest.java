package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStatsQueryTest {
    private static final ResourceRef CPU_A = new ResourceRef("ae2", "cpu", "minecraft:overworld@1,64,0");
    private static final ResourceRef CPU_B = new ResourceRef("ae2", "cpu", "minecraft:overworld@5,64,0");
    private static final ResourceRef UNKNOWN = new ResourceRef("ae2", "cpu", "minecraft:overworld@9,64,0");

    @TempDir
    Path dir;

    private final FakeTime time = new FakeTime();
    private Database db;
    private UptimeClock clock;
    private StorageWriter writer;
    private final Map<ResourceRef, Map<String, String>> cpus = new HashMap<>();

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.open(dir.resolve("overseer.db"));
        var session = db.startSession(time.wallClock().instant());
        clock = new UptimeClock(session.baseUptimeMillis(), session.session(), time, time.wallClock());
        writer = new StorageWriter(db, new SnapshotQueue(100),
                List.of(new ProviderDescriptor("ae2", "AE2", Set.of(), List.of(), 20, 1_000_000)), clock, StorageSettings.DEFAULTS);
        idle(CPU_A);
        idle(CPU_B);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private void idle(ResourceRef cpu) {
        cpus.put(cpu, Map.of("job", "", "jobKind", "", "jobName", "", "jobAmount", ""));
    }

    private void run(ResourceRef cpu, String key, String name, long amount) {
        cpus.put(cpu, Map.of("job", key, "jobKind", "item", "jobName", name, "jobAmount", Long.toString(amount)));
    }

    /** Collects now, then lets {@code seconds} pass. */
    private long collect(long seconds) throws SQLException {
        clock.onTick();
        long at = clock.now().uptimeMillis();
        writer.write(List.of(new ProviderSnapshot("ae2", clock.now(), Map.copyOf(cpus), List.of())));
        for (long i = 0; i < seconds; i++) {
            time.advance(1, TimeUnit.SECONDS);
            clock.onTick();
        }
        return at;
    }

    @Test
    void jobsArePairedWithTheirAmountsAndClippedToTheWindow() throws SQLException {
        long start = collect(10); // both idle
        run(CPU_A, "item:minecraft:diamond", "Diamond", 4);
        collect(30);
        run(CPU_A, "item:minecraft:diamond", "Diamond", 4); // the same amount again: no amount event
        idle(CPU_A);
        collect(5);
        run(CPU_A, "item:minecraft:diamond", "Diamond", 4);
        run(CPU_B, "item:minecraft:iron_ingot", "Iron Ingot", 64);
        long windowFrom = collect(20);
        run(CPU_A, "item:minecraft:gold_ingot", "Gold Ingot", 1); // straight from one job to the next
        long gold = collect(10);
        idle(CPU_A);
        long end = collect(0); // CPU B still runs

        try (var reader = db.openReader()) {
            var all = JobStatsQuery.stats(reader, List.of(CPU_A, CPU_B, UNKNOWN), start, end);
            var diamond = all.items().stream().filter(i -> i.key().equals("item:minecraft:diamond")).findFirst().orElseThrow();
            assertEquals(2, diamond.jobs());
            assertEquals(8, diamond.amount());
            assertEquals("Diamond", diamond.name());
            assertEquals(50_000, diamond.busyMillis());
            assertEquals(30_000, diamond.longestMillis());
            var gold_ = all.items().stream().filter(i -> i.key().equals("item:minecraft:gold_ingot")).findFirst().orElseThrow();
            assertEquals(1, gold_.amount(), "its own amount, not the diamond's");
            assertEquals(10_000, gold_.busyMillis());
            var iron = all.items().stream().filter(i -> i.key().equals("item:minecraft:iron_ingot")).findFirst().orElseThrow();
            assertEquals(30_000, iron.busyMillis(), "still running: counts up to the end");
            assertEquals(0, iron.longestMillis(), "not finished");
            assertEquals(3, all.resources().get(0).jobs());
            assertEquals(60_000, all.resources().get(0).busyMillis());
            assertEquals(0, all.resources().get(2).busyMillis(), "never recorded");

            var later = JobStatsQuery.stats(reader, List.of(CPU_A), windowFrom + 5_000, gold);
            assertEquals(1, later.resources().get(0).jobs(), "the diamond job running at from");
            assertEquals(15_000, later.resources().get(0).busyMillis());
            assertEquals(4, later.items().get(0).amount(), "amount known from before the window");
        }
    }
}
