package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.testing.FakeProvider;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageWriterTest {
    private static final ResourceRef DIESEL = new ResourceRef("flux", "device", "overworld@1,64,1");
    private static final ResourceRef SOLAR = new ResourceRef("flux", "device", "overworld@2,64,2");

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
        provider = new FakeProvider("flux", 20, 1_000_000, time);
        writer = new StorageWriter(db, new SnapshotQueue(100), List.of(provider.descriptor()), clock,
                StorageSettings.DEFAULTS);
    }

    private void restart() throws SQLException {
        db.close();
        open();
    }

    /** Advances one second of uptime, collects and writes synchronously. */
    private void step() throws SQLException {
        clock.onTick();
        time.advance(1, TimeUnit.SECONDS);
        clock.onTick();
        writer.write(List.of(provider.collect(clock.now())));
    }

    private List<String> events() throws SQLException {
        var result = new ArrayList<String>();
        try (var reader = db.openReader();
                var st = reader.createStatement();
                var rs = st.executeQuery("SELECT r.rid, e.kind, e.attribute, e.value FROM event e"
                        + " JOIN resource r ON r.id = e.resource_id ORDER BY e.id")) {
            while (rs.next()) {
                var line = new StringBuilder(rs.getString(1)).append(' ').append(rs.getString(2));
                if (rs.getString(3) != null) line.append(' ').append(rs.getString(3)).append('=').append(rs.getString(4));
                result.add(line.toString());
            }
        }
        return result;
    }

    private List<Double> samples(ResourceRef ref) throws SQLException {
        var result = new ArrayList<Double>();
        try (var reader = db.openReader();
                var ps = reader.prepareStatement("SELECT s.value FROM sample s JOIN series se ON se.id = s.series_id"
                        + " JOIN resource r ON r.id = se.resource_id WHERE r.rid = ? ORDER BY s.uptime_ms")) {
            ps.setString(1, ref.id());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getDouble(1));
            }
        }
        return result;
    }

    @Test
    void recordsAddedResourcesWithAttributes() throws SQLException {
        provider.resource(DIESEL, Map.of("name", "Power plant: Diesel 1"));
        step();
        assertEquals(List.of("overworld@1,64,1 added", "overworld@1,64,1 attribute name=Power plant: Diesel 1"),
                events());
    }

    @Test
    void renameIsAnAttributeEventAndKeepsIdentity() throws SQLException {
        provider.resource(DIESEL, Map.of("name", "Diesel 1")).power(DIESEL, 100);
        step();
        provider.resource(DIESEL, Map.of("name", "Power plant: Diesel 1")).power(DIESEL, 200);
        step();

        assertEquals("overworld@1,64,1 attribute name=Power plant: Diesel 1", events().get(2));
        assertEquals(List.of(100.0, 200.0), samples(DIESEL), "history continues across the rename");
    }

    @Test
    void unchangedStateWritesNoEvents() throws SQLException {
        provider.resource(DIESEL, Map.of("name", "Diesel 1"));
        step();
        step();
        step();
        assertEquals(2, events().size());
    }

    @Test
    void missingResourceIsRecordedAsRemovedAndReAdded() throws SQLException {
        provider.resource(DIESEL, Map.of("name", "Diesel 1")).resource(SOLAR, Map.of("name", "Solar"));
        step();
        provider.remove(SOLAR);
        step();
        provider.resource(SOLAR, Map.of("name", "Solar"));
        step();

        assertEquals(List.of(
                "overworld@1,64,1 added",
                "overworld@1,64,1 attribute name=Diesel 1",
                "overworld@2,64,2 added",
                "overworld@2,64,2 attribute name=Solar",
                "overworld@2,64,2 removed",
                "overworld@2,64,2 added",
                "overworld@2,64,2 attribute name=Solar"), events());
    }

    @Test
    void appliesDeadbandAndHeartbeat() throws SQLException {
        provider.resource(DIESEL, Map.of());
        double[] values = {1000, 1003, 1004, 1500, 1500};
        for (double value : values) {
            provider.power(DIESEL, value);
            step();
        }
        assertEquals(List.of(1000.0, 1500.0), samples(DIESEL));

        for (int i = 0; i < 60; i++) step(); // one minute of unchanged values
        assertEquals(List.of(1000.0, 1500.0, 1500.0), samples(DIESEL), "heartbeat after 60 s");
    }

    @Test
    void skipsNonFiniteValues() throws SQLException {
        provider.resource(DIESEL, Map.of()).power(DIESEL, Double.NaN);
        step();
        assertEquals(List.of(), samples(DIESEL));
    }

    @Test
    void restartDoesNotReRecordKnownResources() throws SQLException {
        provider.resource(DIESEL, Map.of("name", "Diesel 1", "zone", "Power plant"));
        step();
        restart();
        provider.resource(DIESEL, Map.of("name", "Diesel 1", "zone", "Power plant"));
        step();
        assertEquals(3, events().size());
    }

    @Test
    void restartContinuesUptimeInNewSession() throws SQLException {
        provider.resource(DIESEL, Map.of()).power(DIESEL, 1);
        for (int i = 0; i < 10; i++) step();
        long uptimeBefore = clock.uptimeMillis();

        restart();
        time.advanceWallOnly(8, TimeUnit.HOURS); // server was offline

        assertEquals(2, clock.session());
        assertEquals(uptimeBefore, clock.uptimeMillis());
    }

    @Test
    void writerThreadDrainsQueueOnClose() throws SQLException {
        var queue = new SnapshotQueue(100);
        var threaded = new StorageWriter(db, queue, List.of(provider.descriptor()), clock, StorageSettings.DEFAULTS);
        provider.resource(DIESEL, Map.of("name", "Diesel 1"));
        threaded.start();
        for (int i = 0; i < 5; i++) {
            time.advance(1, TimeUnit.SECONDS);
            clock.onTick();
            ProviderSnapshot snapshot = provider.collect(clock.now());
            queue.offer(snapshot);
        }
        threaded.close();
        assertEquals(2, events().size());
    }
}
