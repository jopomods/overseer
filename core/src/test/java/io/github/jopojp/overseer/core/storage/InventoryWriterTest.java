package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Stack;
import io.github.jopojp.overseer.core.testing.FakeProvider;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InventoryWriterTest {
    private static final ResourceRef GRID = new ResourceRef("ae2", "grid", "minecraft:overworld@0,64,0");
    private static final Stack IRON_100 = new Stack("item:minecraft:iron_ingot", "item", "Iron Ingot", 100);
    private static final Stack WATER = new Stack("fluid:minecraft:water", "fluid", "Water", 8_000);

    @TempDir
    Path dir;

    private final FakeTime time = new FakeTime();
    private Database db;
    private UptimeClock clock;
    private FakeProvider provider;
    private StorageWriter writer;

    @BeforeEach
    void setUp() throws SQLException {
        open();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private void open() throws SQLException {
        db = Database.open(dir.resolve("overseer.db"));
        var session = db.startSession(time.wallClock().instant());
        clock = new UptimeClock(session.baseUptimeMillis(), session.session(), time, time.wallClock());
        provider = new FakeProvider("ae2", 20, 1_000_000, time).resource(GRID, Map.of("name", "Base"));
        writer = new StorageWriter(db, new SnapshotQueue(100), List.of(provider.descriptor()), clock,
                StorageSettings.DEFAULTS);
    }

    /** Advances uptime, scans with the given contents and writes synchronously. */
    private void scan(long seconds, Stack... stacks) throws SQLException {
        clock.onTick();
        for (long i = 0; i < seconds; i++) {
            time.advance(1, TimeUnit.SECONDS); // longer gaps count as a pause
            clock.onTick();
        }
        provider.inventory(GRID, List.of(stacks));
        writer.write(List.of(provider.collect(clock.now())));
    }

    private Map<String, InventoryQuery.Change> changesSinceStart() throws SQLException {
        try (var reader = db.openReader()) {
            return InventoryQuery.changes(reader, GRID, 0, 0);
        }
    }

    private long rows(String table) throws SQLException {
        try (var reader = db.openReader(); var st = reader.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void firstScanIsABaselineNotAChange() throws SQLException {
        scan(1, IRON_100, WATER);
        assertTrue(changesSinceStart().isEmpty());
        assertEquals(2, rows("inventory_amount"));
    }

    @Test
    void onlyChangesAreWrittenAndSummedPerMinute() throws SQLException {
        scan(1, IRON_100, WATER);
        scan(10, new Stack(IRON_100.key(), "item", "Iron Ingot", 90), WATER);
        scan(10, new Stack(IRON_100.key(), "item", "Iron Ingot", 70), WATER);
        scan(10, new Stack(IRON_100.key(), "item", "Iron Ingot", 70), WATER);

        var changes = changesSinceStart();
        assertEquals(Map.of(IRON_100.key(), new InventoryQuery.Change("item", "Iron Ingot", -30, -30)), changes);
        assertEquals(1, rows("inventory_change"), "both changes fall into one minute");
    }

    @Test
    void goneKeysCountAsUsedUpAndNewKeysAsGained() throws SQLException {
        scan(1, IRON_100, WATER);
        scan(10, IRON_100, new Stack("item:minecraft:gold_ingot", "item", "Gold Ingot", 5));

        var changes = changesSinceStart();
        assertEquals(-8_000, changes.get(WATER.key()).longer());
        assertEquals(5, changes.get("item:minecraft:gold_ingot").longer());
        assertEquals(2, rows("inventory_amount"));
    }

    @Test
    void restartKeepsTheBaseline() throws SQLException {
        scan(1, IRON_100);
        writer.close();
        db.close();
        open();
        scan(1, IRON_100);
        assertTrue(changesSinceStart().isEmpty(), "unchanged contents after a restart are no change");
        scan(1, new Stack(IRON_100.key(), "item", "Iron Ingot", 120));
        assertEquals(20, changesSinceStart().get(IRON_100.key()).longer());
    }

    @Test
    void windowsSplitByMinute() throws SQLException {
        scan(1, IRON_100);
        scan(10, new Stack(IRON_100.key(), "item", "Iron Ingot", 50));
        for (int i = 0; i < 12; i++) scan(10, new Stack(IRON_100.key(), "item", "Iron Ingot", 50));
        scan(10, new Stack(IRON_100.key(), "item", "Iron Ingot", 60));
        long now = clock.uptimeMillis();
        try (var reader = db.openReader()) {
            var change = InventoryQuery.changes(reader, GRID, now - 60_000, 0).get(IRON_100.key());
            assertEquals(10, change.recent());
            assertEquals(-40, change.longer());
        }
    }

    @Test
    void renamedKeysKeepTheirHistory() throws SQLException {
        scan(1, IRON_100);
        scan(10, new Stack(IRON_100.key(), "item", "Eisenbarren", 99));
        assertEquals("Eisenbarren", changesSinceStart().get(IRON_100.key()).name());
        assertEquals(1, rows("inventory_key"));
    }
}
