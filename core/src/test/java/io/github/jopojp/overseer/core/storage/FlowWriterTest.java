package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.Flow;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Stack;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowWriterTest {
    private static final ResourceRef GRID = new ResourceRef("ae2", "grid", "minecraft:overworld@0,64,0");
    private static final Stack IRON = new Stack("item:minecraft:iron_ingot", "item", "Iron Ingot", 0);
    private static final Stack ORE = new Stack("item:minecraft:raw_iron", "item", "Raw Iron", 0);
    private static final String BUS = "minecraft:overworld@1,64,0/east";

    @TempDir
    Path dir;

    private final FakeTime time = new FakeTime();
    private Database db;
    private UptimeClock clock;
    private StorageWriter writer;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.open(dir.resolve("overseer.db"));
        var session = db.startSession(time.wallClock().instant());
        clock = new UptimeClock(session.baseUptimeMillis(), session.session(), time, time.wallClock());
        writer = new StorageWriter(db, new SnapshotQueue(100),
                List.of(new ProviderDescriptor("ae2", "AE2", Set.of(), List.of(), 20, 1_000_000)), clock, StorageSettings.DEFAULTS);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private void collect(long seconds, Flow... flows) throws SQLException {
        clock.onTick();
        for (long i = 0; i < seconds; i++) {
            time.advance(1, TimeUnit.SECONDS);
            clock.onTick();
        }
        writer.write(List.of(new ProviderSnapshot("ae2", clock.now(), Map.of(GRID, Map.of("name", "Base")), List.of(),
                Map.of(), Map.of(), Map.of(), Map.of(GRID, List.of(flows)))));
    }

    @Test
    void flowsAreSummedPerDeviceKeyAndMinute() throws SQLException {
        collect(1, new Flow(BUS, "import_bus", "ME Import Bus", ORE, 16, 0),
                new Flow("player:00000000-0000-0000-0000-000000000001", "player", "Steve", IRON, 0, 64));
        collect(1, new Flow(BUS, "import_bus", "Ore intake", ORE, 8, 0));
        collect(1, new Flow(BUS, "import_bus", "Ore intake", ORE, 0, 0)); // nothing moved: no row
        collect(70, new Flow(BUS, "import_bus", "Ore intake", ORE, 4, 1));

        try (var reader = db.openReader()) {
            var rows = FlowQuery.totals(reader, GRID, 0, clock.uptimeMillis() + 1);
            assertEquals(2, rows.size());
            assertEquals(64, rows.get(0).extracted(), "largest first: the player");
            assertEquals("Iron Ingot", rows.get(0).name());
            var bus = rows.get(1);
            assertEquals(BUS, bus.device());
            assertEquals("Ore intake", bus.deviceName(), "the newest name");
            assertEquals(28, bus.inserted());
            assertEquals(1, bus.extracted());

            var minutes = FlowQuery.perMinute(reader, GRID, ORE.key(), null, 0, clock.uptimeMillis() + 1);
            assertEquals(2, minutes.size(), "two minutes with flow");
            assertEquals(24, minutes.get(0)[1]);
            assertEquals(4, minutes.get(1)[1]);
            assertEquals(1, FlowQuery.perMinute(reader, GRID, null, "player:00000000-0000-0000-0000-000000000001", 0,
                    clock.uptimeMillis() + 1).size());
        }
    }
}
