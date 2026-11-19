package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.storage.StepBuckets.Step;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds minute and hour rollups and applies retention (ADR-0005, ADR-0006).
 * Runs on the storage writer thread, inside its transaction.
 *
 * <p>Progress is tracked by watermarks in {@code meta} (exclusive end, server uptime ms):
 * minutes are rolled up once complete (with one minute of slack for late writes),
 * hours once their minutes are. Raw samples are only deleted after they were rolled up.
 */
final class Rollups {
    static final long MINUTE = 60_000;
    static final long HOUR = 3_600_000;
    private static final String MINUTE_MARK = "rollup.minute";
    private static final String HOUR_MARK = "rollup.hour";

    private Rollups() {}

    static void run(Connection c, long nowUptime, StorageSettings settings) throws SQLException {
        List<Sessions.Session> sessions = Sessions.list(c);
        long minuteMark = rollMinutes(c, nowUptime, sessions);
        long hourMark = rollHours(c, minuteMark, sessions);
        retain(c, nowUptime, minuteMark, hourMark, settings);
    }

    private static long rollMinutes(Connection c, long now, List<Sessions.Session> sessions) throws SQLException {
        long target = Math.floorDiv(now - MINUTE, MINUTE) * MINUTE;
        Long mark = mark(c, MINUTE_MARK);
        if (mark == null) {
            Long first = scalar(c, "SELECT MIN(uptime_ms) FROM sample");
            if (first == null) return 0;
            mark = Math.floorDiv(first, MINUTE) * MINUTE;
        }
        if (target <= mark) return mark;

        Map<Long, List<Step>> steps = new HashMap<>();
        // Carried value: the last sample before the window, per series.
        try (var ps = c.prepareStatement("SELECT s.series_id, s.uptime_ms, s.value FROM sample s WHERE s.uptime_ms ="
                + " (SELECT MAX(uptime_ms) FROM sample x WHERE x.series_id = s.series_id AND x.uptime_ms < ?)")) {
            ps.setLong(1, mark);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) steps.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(Step.of(rs.getLong(2), rs.getDouble(3)));
            }
        }
        try (var ps = c.prepareStatement("SELECT series_id, uptime_ms, value FROM sample"
                + " WHERE uptime_ms >= ? AND uptime_ms < ? ORDER BY series_id, uptime_ms")) {
            ps.setLong(1, mark);
            ps.setLong(2, target);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) steps.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(Step.of(rs.getLong(2), rs.getDouble(3)));
            }
        }
        write(c, "sample_minute", steps, mark, target, MINUTE, sessions);
        setMark(c, MINUTE_MARK, target);
        return target;
    }

    private static long rollHours(Connection c, long minuteMark, List<Sessions.Session> sessions) throws SQLException {
        long target = Math.floorDiv(minuteMark, HOUR) * HOUR;
        Long mark = mark(c, HOUR_MARK);
        if (mark == null) {
            Long first = scalar(c, "SELECT MIN(bucket) FROM sample_minute");
            if (first == null) return 0;
            mark = Math.floorDiv(first, HOUR) * HOUR;
        }
        if (target <= mark) return mark;

        Map<Long, List<Step>> steps = new HashMap<>();
        try (var ps = c.prepareStatement("SELECT m.series_id, m.bucket, m.min, m.max, m.avg, m.last FROM sample_minute m"
                + " WHERE m.bucket = (SELECT MAX(bucket) FROM sample_minute x WHERE x.series_id = m.series_id AND x.bucket < ?)"
                + " UNION ALL SELECT series_id, bucket, min, max, avg, last FROM sample_minute"
                + " WHERE bucket >= ? AND bucket < ? ORDER BY 1, 2")) {
            ps.setLong(1, mark);
            ps.setLong(2, mark);
            ps.setLong(3, target);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    steps.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Step(rs.getLong(2),
                            rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)));
                }
            }
        }
        // A carried minute row only contributes its closing value to the next hour.
        final long windowStart = mark;
        steps.replaceAll((id, list) -> {
            if (!list.isEmpty() && list.get(0).start() < windowStart) {
                Step carry = list.get(0);
                var copy = new ArrayList<>(list);
                copy.set(0, Step.of(carry.start(), carry.last()));
                return copy;
            }
            return list;
        });
        write(c, "sample_hour", steps, mark, target, HOUR, sessions);
        setMark(c, HOUR_MARK, target);
        return target;
    }

    private static void write(Connection c, String table, Map<Long, List<Step>> steps, long from, long to, long width,
            List<Sessions.Session> sessions) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO " + table
                + " (series_id, bucket, wall_ms, session, min, max, avg, last) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (var entry : steps.entrySet()) {
                for (StepBuckets.Bucket b : StepBuckets.aggregate(entry.getValue(), from, to, width)) {
                    ps.setLong(1, entry.getKey());
                    ps.setLong(2, b.start());
                    ps.setLong(3, Sessions.wallAt(sessions, b.start()));
                    ps.setInt(4, Sessions.at(sessions, b.start()).number());
                    ps.setDouble(5, b.min());
                    ps.setDouble(6, b.max());
                    ps.setDouble(7, b.avg());
                    ps.setDouble(8, b.last());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    /** Deletes data older than its retention, but never raw data that was not rolled up yet. */
    private static void retain(Connection c, long now, long minuteMark, long hourMark, StorageSettings s)
            throws SQLException {
        if (s.rawRetentionMillis() > 0) {
            delete(c, "DELETE FROM sample WHERE uptime_ms < ?", Math.min(now - s.rawRetentionMillis(), minuteMark));
            // Inventory changes are minute buckets already but far more numerous: raw retention.
            delete(c, "DELETE FROM inventory_change WHERE bucket < ?", now - s.rawRetentionMillis());
            // Flows likewise (ADR-0020).
            delete(c, "DELETE FROM flow_minute WHERE bucket < ?", now - s.rawRetentionMillis());
        }
        if (s.minuteRetentionMillis() > 0) {
            delete(c, "DELETE FROM sample_minute WHERE bucket < ?", Math.min(now - s.minuteRetentionMillis(), hourMark));
        }
        if (s.hourRetentionMillis() > 0) {
            delete(c, "DELETE FROM sample_hour WHERE bucket < ?", now - s.hourRetentionMillis());
        }
    }

    private static void delete(Connection c, String sql, long before) throws SQLException {
        if (before <= 0) return;
        try (var ps = c.prepareStatement(sql)) {
            ps.setLong(1, before);
            ps.executeUpdate();
        }
    }

    private static Long mark(Connection c, String key) throws SQLException {
        try (var ps = c.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Long.valueOf(rs.getString(1)) : null;
            }
        }
    }

    private static void setMark(Connection c, String key, long value) throws SQLException {
        try (var ps = c.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, Long.toString(value));
            ps.executeUpdate();
        }
    }

    private static Long scalar(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            if (!rs.next()) return null;
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        }
    }
}
