package io.github.jopojp.overseer.core.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.Database;
import io.github.jopojp.overseer.core.storage.StorageSettings;
import io.github.jopojp.overseer.core.storage.StorageWriterAccess;
import io.github.jopojp.overseer.core.testing.FakeProvider;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingTest {
    private static final String REAL_UUID = "5084e6f3-8f54-43f1-8df5-1dca109e430f";

    @TempDir
    Path dir;

    private static Recording sample() {
        var frame = new Recording.Frame("flux", Instant.parse("2026-01-01T00:00:00Z"), 1000, 1, List.of(
                new Recording.Entry("network", "1", Map.of("name", "Main", "owner", REAL_UUID), Map.of("energy_input", 10.0)),
                new Recording.Entry("member", "1:" + REAL_UUID, Map.of("player", REAL_UUID, "name", "Dev", "access", "owner"), Map.of()),
                new Recording.Entry("device", "minecraft:overworld@100,64,-200",
                        Map.of("name", "Device 7", "pos", "100,64,-200", "owner", REAL_UUID), Map.of("transfer", 5.0))));
        var server = new Recording.Frame("server", Instant.parse("2026-01-01T00:00:00Z"), 1000, 1, List.of(
                new Recording.Entry("server", "main", Map.of("motd", "Dev - My World"), Map.of("tps", 20.0)),
                new Recording.Entry("player", REAL_UUID, Map.of("name", "Dev"), Map.of("ping", 3.0))));
        return new Recording(Recording.Header.of("test", 5000, false), List.of(frame, server));
    }

    @Test
    void roundTripsThroughJsonLines() throws IOException {
        var out = new StringWriter();
        RecordingIO.write(sample(), out);
        assertEquals(3, out.toString().split("\n").length, "header + one line per frame");
        assertEquals(sample(), RecordingIO.read(new StringReader(out.toString())));
    }

    @Test
    void rejectsForeignAndNewerFiles() {
        assertThrows(IOException.class, () -> RecordingIO.read(new StringReader("{\"format\":\"other\"}\n")));
        assertThrows(IOException.class, () -> RecordingIO.read(new StringReader(
                "{\"format\":\"overseer-recording\",\"version\":99}\n")));
    }

    @Test
    void anonymizerReplacesPlayersCoordinatesAndMotdButKeepsDeviceNames() throws IOException {
        Recording anonymized = Anonymizer.anonymize(sample(), 42);
        var text = new StringWriter();
        RecordingIO.write(anonymized, text);
        String json = text.toString();

        assertTrue(anonymized.header().anonymized());
        assertFalse(json.contains(REAL_UUID));
        assertFalse(json.contains("\"Dev\""));
        assertFalse(json.contains("My World"));
        assertTrue(json.contains("Device 7"), "whole-word replacement must not touch 'Device'");
        assertTrue(json.contains("Player1"));

        var device = anonymized.snapshots().get(0).resources().get(2);
        String[] pos = device.attributes().get("pos").split(",");
        assertNotEquals("100", pos[0]);
        assertEquals(0, (Integer.parseInt(pos[0]) - 100) % 16, "offset keeps chunk alignment");
        assertEquals("64", pos[1], "height is kept");
        assertTrue(device.id().endsWith("@" + device.attributes().get("pos")), "id and pos shifted alike");
    }

    @Test
    void anonymizerCoversAe2OwnersAndEveryPosition() {
        String owner = "0b1c2d3e-4f50-4617-8899-aabbccddeeff";
        var grid = new Recording.Entry("grid", "minecraft:overworld@120,64,-340",
                Map.of("owner", owner, "ownerName", "Alexandra", "pos", "120,64,-340", "name", "Alexandra's base"),
                Map.of());
        var cpu = new Recording.Entry("cpu", "minecraft:overworld@130,70,-330",
                Map.of("grid", "minecraft:overworld@120,64,-340", "name", "Main"), Map.of());
        var recording = new Recording(new Recording.Header("overseer-recording", 1, "t", 5000, false),
                List.of(new Recording.Frame("ae2", Instant.EPOCH, 0, 1, List.of(grid, cpu))));

        var out = Anonymizer.anonymize(recording, 7).snapshots().get(0).resources();
        var g = out.get(0);
        var c = out.get(1);
        assertFalse(g.attributes().toString().contains("Alexandra"), "owner name replaced, also inside other names");
        assertFalse(g.attributes().toString().contains(owner));
        assertNotEquals("minecraft:overworld@120,64,-340", g.id(), "grid position shifted");
        assertTrue(g.id().endsWith("@" + g.attributes().get("pos")), "id and pos shifted alike");
        assertEquals(g.id(), c.attributes().get("grid"), "references to the grid shifted alike");
        assertNotEquals("minecraft:overworld@130,70,-330", c.id(), "cpu position shifted");
    }

    @Test
    void exportSelectsProvidersAndTheLastMinutes() {
        var frames = List.of(
                new Recording.Frame("ae2", Instant.EPOCH, 0, 1, List.of()),
                new Recording.Frame("flux", Instant.EPOCH, 0, 1, List.of()),
                new Recording.Frame("ae2", Instant.EPOCH, 50 * 60_000, 1, List.of()),
                new Recording.Frame("ae2", Instant.EPOCH, 60 * 60_000, 1, List.of()));
        var recording = new Recording(new Recording.Header("overseer-recording", 1, "t", 5000, false), frames);

        var selected = ExportRecordingMain.select(recording, java.util.Set.of("ae2"), 15 * 60_000);
        assertEquals(List.of(50 * 60_000L, 60 * 60_000L),
                selected.snapshots().stream().map(Recording.Frame::uptimeMillis).toList());
        assertEquals(4, ExportRecordingMain.select(recording, java.util.Set.of(), 0).snapshots().size());
    }

    @Test
    void replayLoopsAndRestampsFrames() {
        var replay = new ReplayProvider(sample(), "flux");
        var at = new Timestamp(Instant.parse("2030-01-01T00:00:00Z"), 99, 7);
        var first = replay.collect(at);
        var second = replay.collect(at);
        assertEquals(at, first.at());
        assertEquals(first.resources(), second.resources(), "single frame loops");
        assertEquals(100, replay.descriptor().intervalTicks(), "5000 ms = 100 ticks");
    }

    @Test
    void exporterRebuildsStateFromDatabase() throws Exception {
        var time = new FakeTime();
        var a = new ResourceRef("flux", "device", "minecraft:overworld@1,64,1");
        var b = new ResourceRef("flux", "device", "minecraft:overworld@2,64,2");
        var provider = new FakeProvider("flux", 20, 1_000_000, time)
                .resource(a, Map.of("name", "Old")).resource(b, Map.of("name", "B")).power(a, 100);
        try (var db = Database.open(dir.resolve("overseer.db"))) {
            var session = db.startSession(time.wallClock().instant());
            var clock = new UptimeClock(session.baseUptimeMillis(), session.session(), time, time.wallClock());
            var writer = StorageWriterAccess.create(db, new SnapshotQueue(10), List.of(provider.descriptor()), clock,
                    StorageSettings.DEFAULTS);
            for (int second = 0; second <= 20; second++) {
                if (second == 10) provider.resource(a, Map.of("name", "New")).power(a, 300).remove(b);
                clock.onTick();
                StorageWriterAccess.write(writer, provider.collect(clock.now()));
                time.advance(1, TimeUnit.SECONDS);
                clock.onTick();
            }

            Recording recording;
            try (var reader = db.openReader()) {
                recording = RecordingExporter.export(reader, 5_000, "test");
            }
            var frames = recording.snapshots();
            assertEquals(5, frames.size(), "0, 5, 10, 15, 20 s");
            var early = frames.get(1);
            var late = frames.get(4);
            assertEquals("Old", attribute(early, a, "name"));
            assertEquals(100.0, metric(early, a, "power"));
            assertEquals("New", attribute(late, a, "name"));
            assertEquals(300.0, metric(late, a, "power"));
            assertNull(entry(late, b), "removed resource is gone");
        }
    }

    /** Guard for everything committed under fixtures/recordings. */
    @Test
    void committedRecordingsAreAnonymized() throws IOException {
        Path fixtures = Path.of("../fixtures/recordings");
        Pattern uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        try (Stream<Path> files = Files.list(fixtures)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".jsonl")).toList()) {
                Recording recording = RecordingIO.read(file);
                assertTrue(recording.header().anonymized(), file + " must be anonymized");
                assertFalse(recording.snapshots().isEmpty(), file + " is empty");
                var m = uuid.matcher(Files.readString(file));
                while (m.find()) {
                    assertTrue(m.group().startsWith("00000000-0000-4000-8000-"), file + " contains a real UUID");
                }
            }
        }
    }

    private static Recording.Entry entry(Recording.Frame frame, ResourceRef ref) {
        return frame.resources().stream()
                .filter(e -> e.type().equals(ref.type()) && e.id().equals(ref.id()))
                .findFirst().orElse(null);
    }

    private static String attribute(Recording.Frame frame, ResourceRef ref, String key) {
        return entry(frame, ref).attributes().get(key);
    }

    private static Double metric(Recording.Frame frame, ResourceRef ref, String key) {
        return entry(frame, ref).metrics().get(key);
    }
}
