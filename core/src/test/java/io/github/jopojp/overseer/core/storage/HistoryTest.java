package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.StepBuckets.Step;
import io.github.jopojp.overseer.core.testing.FakeProvider;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryTest {
    private static final ResourceRef NET = new ResourceRef("flux", "network", "1");
    private static final long MIN = 60_000;
    private static final long HOUR = 3_600_000;

    // --- StepBuckets ------------------------------------------------------------------

    @Test
    void bucketsAreTimeWeighted() {
        // 100 for the first 45 s, 200 for the last 15 s of the minute.
        var buckets = StepBuckets.aggregate(List.of(Step.of(0, 100), Step.of(45_000, 200)), 0, MIN, MIN);
        assertEquals(1, buckets.size());
        assertEquals(125, buckets.get(0).avg(), 1e-9);
        assertEquals(100, buckets.get(0).min());
        assertEquals(200, buckets.get(0).max());
        assertEquals(200, buckets.get(0).last());
    }

    @Test
    void valuesCarryIntoLaterBucketsAndGapsBeforeFirstStepAreUncovered() {
        var buckets = StepBuckets.aggregate(List.of(Step.of(30_000, 7)), 0, 3 * MIN, MIN);
        assertEquals(3, buckets.size());
        assertEquals(30_000, buckets.get(0).coverage());
        assertEquals(7, buckets.get(2).avg());
        assertEquals(MIN, buckets.get(2).coverage(), "carried value covers the whole bucket");
    }

    @Test
    void carriedStepBeforeWindowIsUsed() {
        var buckets = StepBuckets.aggregate(List.of(Step.of(-10 * MIN, 5), Step.of(MIN + 1, 9)), 0, 2 * MIN, MIN);
        assertEquals(5, buckets.get(0).avg());
        assertTrue(buckets.get(1).avg() > 8.9);
    }

    // --- Rollups, retention and queries through the real writer -------------------------

    @TempDir
    Path dir;

    private final FakeTime time = new FakeTime();
    private Database db;
    private UptimeClock clock;
    private StorageWriter writer;
    private FakeProvider provider;

    private void open(StorageSettings settings) throws SQLException {
        db = Database.open(dir.resolve("overseer.db"));
        var session = db.startSession(time.wallClock().instant());
        clock = new UptimeClock(session.baseUptimeMillis(), session.session(), time, time.wallClock());
        provider = new FakeProvider("flux", 20, 1_000_000, time).resource(NET, Map.of("name", "Main"));
        writer = new StorageWriter(db, new SnapshotQueue(10), List.of(provider.descriptor()), clock, settings);
    }

    @AfterEach
    void close() throws SQLException {
        if (db != null) db.close();
    }

    /** Runs {@code seconds} of server time at 1 Hz with power given by the function. */
    private void run(int seconds, java.util.function.IntToDoubleFunction power) throws SQLException {
        for (int s = 0; s < seconds; s++) {
            clock.onTick();
            provider.power(NET, power.applyAsDouble(s));
            writer.write(List.of(provider.collect(clock.now())));
            time.advance(1, TimeUnit.SECONDS);
            clock.onTick();
        }
    }

    private long count(String table) throws SQLException {
        try (var r = db.openReader(); var st = r.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void minuteAndHourRollupsAreTimeWeighted() throws SQLException {
        open(StorageSettings.DEFAULTS);
        // Two hours: 1000 FE/t, except the first 15 s of every minute at 3000.
        run(2 * 3600 + 120, s -> s % 60 < 15 ? 3000 : 1000);
        writer.rollup(clock.uptimeMillis());

        try (var reader = db.openReader()) {
            var minutes = HistoryQuery.query(reader, NET, List.of("power"), 0, 60 * MIN, 60);
            assertEquals(HistoryQuery.Resolution.MINUTE, minutes.resolution());
            var p = minutes.series().get(0).points().get(5);
            assertEquals(1500, p.avg(), 1.0, "15 s at 3000 + 45 s at 1000");
            assertEquals(3000, p.max());
            assertEquals(1000, p.min());

            var hours = HistoryQuery.query(reader, NET, List.of("power"), 0, 2 * HOUR, 2);
            assertEquals(HistoryQuery.Resolution.HOUR, hours.resolution());
            assertEquals(1500, hours.series().get(0).points().get(0).avg(), 1.0);
        }
        assertTrue(count("sample_hour") >= 1);
    }

    @Test
    void rawQueryCoversRecentDataNotYetRolledUp() throws SQLException {
        open(StorageSettings.DEFAULTS);
        run(300, s -> s < 150 ? 10 : 20);
        try (var reader = db.openReader()) {
            var history = HistoryQuery.query(reader, NET, List.of("power", "missing"), 0, 300_000, 300);
            assertEquals(HistoryQuery.Resolution.RAW, history.resolution());
            var points = history.series().get(0).points();
            assertEquals(10, points.get(10).avg());
            assertEquals(20, points.get(200).avg());
            assertTrue(history.series().get(1).points().isEmpty(), "unknown metric yields no points");
            assertEquals(1, points.get(0).session());
            assertTrue(points.get(0).wall() > 0);
        }
    }

    @Test
    void retentionDeletesOnlyRolledUpRawData() throws SQLException {
        var shortRaw = new StorageSettings(256, 60_000, 5_000, 60_000, 10 * MIN, 0, 0);
        open(shortRaw);
        run(30 * 60, s -> s);
        long rawBefore = count("sample");
        writer.rollup(clock.uptimeMillis());
        long rawAfter = count("sample");
        assertTrue(rawAfter < rawBefore, "old raw samples were deleted");
        try (var reader = db.openReader()) {
            // The deleted part is still answered from minute rollups.
            var history = HistoryQuery.query(reader, NET, List.of("power"), 0, 30 * MIN, 30);
            assertTrue(history.series().get(0).points().size() >= 28);
        }
    }
}
