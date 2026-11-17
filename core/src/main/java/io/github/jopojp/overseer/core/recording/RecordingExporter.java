package io.github.jopojp.overseer.core.recording;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Rebuilds a {@link Recording} from an Overseer database (ADR-0005, ADR-0006).
 *
 * <p>The database stores events and thinned samples. This replays them on a fixed grid of
 * server uptime: at each grid point every provider gets a frame with the resources present
 * at that moment, their attributes as of then and the last stored value of each metric
 * (the deadband guarantees it is still accurate). Wall time comes from the uptime session.
 */
public final class RecordingExporter {
    private RecordingExporter() {}

    private record Session(int number, long startUptime, long lastUptime, long startWall) {}

    private record Change(long uptime, String kind, String attribute, String value) {}

    private record Point(long uptime, double value) {}

    public static Recording export(Connection db, long intervalMillis, String description) throws SQLException {
        Map<Long, ResourceRef> resources = new HashMap<>();
        try (var st = db.createStatement(); var rs = st.executeQuery("SELECT id, provider, type, rid FROM resource")) {
            while (rs.next()) resources.put(rs.getLong(1), new ResourceRef(rs.getString(2), rs.getString(3), rs.getString(4)));
        }

        Map<Long, List<Change>> changes = new HashMap<>();
        try (var st = db.createStatement();
                var rs = st.executeQuery("SELECT resource_id, uptime_ms, kind, attribute, value FROM event ORDER BY id")) {
            while (rs.next()) {
                changes.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                        .add(new Change(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        }

        // resource -> metric -> points ordered by uptime
        Map<Long, Map<String, List<Point>>> series = new HashMap<>();
        long minUptime = Long.MAX_VALUE;
        long maxUptime = Long.MIN_VALUE;
        try (var st = db.createStatement();
                var rs = st.executeQuery("SELECT se.resource_id, se.metric, s.uptime_ms, s.value FROM sample s"
                        + " JOIN series se ON se.id = s.series_id ORDER BY s.uptime_ms")) {
            while (rs.next()) {
                long uptime = rs.getLong(3);
                minUptime = Math.min(minUptime, uptime);
                maxUptime = Math.max(maxUptime, uptime);
                series.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                        .computeIfAbsent(rs.getString(2), k -> new ArrayList<>())
                        .add(new Point(uptime, rs.getDouble(4)));
            }
        }

        List<Session> sessions = new ArrayList<>();
        try (var st = db.createStatement(); var rs = st.executeQuery(
                "SELECT session, started_uptime_ms, last_uptime_ms, started_wall_ms FROM uptime_session ORDER BY session")) {
            while (rs.next()) sessions.add(new Session(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        }

        // Samples are thinned (deadband), so the last stored sample can be long before the end of
        // the recorded time. The sessions know how long the server actually ran.
        for (Session s : sessions) maxUptime = Math.max(maxUptime, s.lastUptime());
        for (List<Change> list : changes.values()) {
            for (Change c : list) maxUptime = Math.max(maxUptime, c.uptime());
        }

        var frames = new ArrayList<Recording.Frame>();
        if (minUptime == Long.MAX_VALUE) {
            return new Recording(Recording.Header.of(description, intervalMillis, false), frames);
        }

        var providers = new TreeSet<String>();
        resources.values().forEach(r -> providers.add(r.provider()));

        for (long time = minUptime; time <= maxUptime; time += intervalMillis) {
            final long t = time;
            Session session = sessionAt(sessions, t);
            Instant wall = Instant.ofEpochMilli(session.startWall() + (t - session.startUptime()));
            for (String provider : providers) {
                var entries = new ArrayList<Recording.Entry>();
                var ids = new ArrayList<>(resources.keySet());
                ids.sort(java.util.Comparator.comparing(id -> resources.get(id).key()));
                for (long id : ids) {
                    ResourceRef ref = resources.get(id);
                    if (!ref.provider().equals(provider)) continue;
                    Map<String, String> attributes = stateAt(changes.getOrDefault(id, List.of()), t);
                    if (attributes == null) continue; // not present at t
                    Map<String, Double> metrics = new TreeMap<>();
                    series.getOrDefault(id, Map.of()).forEach((metric, points) -> {
                        Double value = valueAt(points, t);
                        if (value != null) metrics.put(metric, value);
                    });
                    entries.add(new Recording.Entry(ref.type(), ref.id(), attributes, metrics));
                }
                frames.add(new Recording.Frame(provider, wall, t, session.number(), entries));
            }
        }
        return new Recording(Recording.Header.of(description, intervalMillis, false), frames);
    }

    /** Attributes at uptime {@code t}, or {@code null} if the resource was not present. */
    private static Map<String, String> stateAt(List<Change> changes, long t) {
        Map<String, String> attributes = null;
        for (Change c : changes) {
            if (c.uptime() > t) break;
            switch (c.kind()) {
                case "added" -> attributes = new TreeMap<>();
                case "removed" -> attributes = null;
                case "attribute" -> {
                    if (attributes == null) attributes = new TreeMap<>();
                    if (c.value() == null) attributes.remove(c.attribute()); else attributes.put(c.attribute(), c.value());
                }
                default -> { }
            }
        }
        return attributes;
    }

    private static Double valueAt(List<Point> points, long t) {
        Double value = null;
        for (Point p : points) {
            if (p.uptime() > t) break;
            value = p.value();
        }
        return value;
    }

    private static Session sessionAt(List<Session> sessions, long t) {
        Session match = sessions.isEmpty() ? new Session(1, 0, Long.MAX_VALUE, 0) : sessions.get(0);
        for (Session s : sessions) if (s.startUptime() <= t) match = s;
        return match;
    }
}
