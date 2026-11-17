package io.github.jopojp.overseer.core.recording;

import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A sequence of snapshots over time, for tests, the simulator and bug reproduction
 * (ADR-0005). Stored as JSON Lines by {@link RecordingIO}: one header line, then one
 * snapshot per line. Versioned so old recordings stay readable.
 *
 * @param header    format metadata
 * @param snapshots snapshots of all providers, ordered by uptime
 */
public record Recording(Header header, List<Frame> snapshots) {
    public static final String FORMAT = "overseer-recording";
    public static final int VERSION = 1;

    public Recording {
        Objects.requireNonNull(header, "header");
        snapshots = List.copyOf(snapshots);
    }

    /**
     * @param format         always {@value #FORMAT}
     * @param version        format version, currently {@value #VERSION}
     * @param description    free text: where the data comes from
     * @param intervalMillis spacing of the snapshots per provider
     * @param anonymized     players, coordinates and server name were replaced
     */
    public record Header(String format, int version, String description, long intervalMillis, boolean anonymized) {
        public static Header of(String description, long intervalMillis, boolean anonymized) {
            return new Header(FORMAT, VERSION, description, intervalMillis, anonymized);
        }
    }

    /** One provider snapshot in serializable form. */
    public record Frame(String provider, Instant wall, long uptimeMillis, int session, List<Entry> resources) {
        public Frame {
            resources = List.copyOf(resources);
        }

        public static Frame of(ProviderSnapshot snapshot) {
            Map<ResourceRef, Map<String, Double>> metrics = new HashMap<>();
            for (Sample s : snapshot.samples()) {
                metrics.computeIfAbsent(s.resource(), r -> new LinkedHashMap<>()).put(s.metric(), s.value());
            }
            var entries = new ArrayList<Entry>();
            snapshot.resources().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(ResourceRef::key)))
                    .forEach(e -> entries.add(new Entry(e.getKey().type(), e.getKey().id(),
                            new java.util.TreeMap<>(e.getValue()), metrics.getOrDefault(e.getKey(), Map.of()))));
            return new Frame(snapshot.provider(), snapshot.at().wall(), snapshot.at().uptimeMillis(),
                    snapshot.at().session(), entries);
        }

        /** Back to a snapshot, stamped with {@code at} (replay uses the current time). */
        public ProviderSnapshot toSnapshot(Timestamp at) {
            Map<ResourceRef, Map<String, String>> resources = new HashMap<>();
            List<Sample> samples = new ArrayList<>();
            for (Entry e : this.resources) {
                var ref = new ResourceRef(provider, e.type(), e.id());
                resources.put(ref, e.attributes());
                e.metrics().forEach((metric, value) -> samples.add(new Sample(ref, metric, value)));
            }
            return new ProviderSnapshot(provider, at, resources, samples);
        }

        public ProviderSnapshot toSnapshot() {
            return toSnapshot(new Timestamp(wall, uptimeMillis, session));
        }
    }

    /** One resource of a frame. */
    public record Entry(String type, String id, Map<String, String> attributes, Map<String, Double> metrics) {
        public Entry {
            attributes = Map.copyOf(attributes);
            metrics = Map.copyOf(metrics);
        }
    }
}
