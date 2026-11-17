package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.StepBuckets.Step;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads history for charts (ADR-0005). The caller asks for a time range in server uptime
 * and a maximum number of points; the query picks the coarsest stored resolution that
 * still gives that many points and aggregates time-weighted into equal buckets.
 *
 * <ul>
 *   <li>bucket &lt; 1 min: raw samples (step function)</li>
 *   <li>bucket &lt; 1 h: minute rollups (bucket rounded to whole minutes)</li>
 *   <li>otherwise: hour rollups (bucket rounded to whole hours)</li>
 * </ul>
 * Recent minutes not yet rolled up are filled from raw samples, so the newest part of a
 * long-range chart is never missing.
 */
public final class HistoryQuery {
    private HistoryQuery() {}

    public enum Resolution { RAW, MINUTE, HOUR }

    /** One aggregated point; {@code uptime} = bucket start. */
    public record Point(long uptime, long wall, int session, double min, double max, double avg, double last) {}

    public record Series(String metric, List<Point> points) {}

    public record History(Resolution resolution, long from, long to, long bucketMillis, List<Series> series) {}

    public static History query(Connection c, ResourceRef ref, List<String> metrics, long from, long to, int maxPoints)
            throws SQLException {
        if (to <= from) throw new IllegalArgumentException("to must be after from");
        long span = to - from;
        long bucket = Math.max(1_000, ceilDiv(span, Math.max(1, maxPoints)));
        Resolution resolution;
        if (bucket < Rollups.MINUTE) {
            resolution = Resolution.RAW;
            bucket = ceilDiv(bucket, 1_000) * 1_000;
        } else if (bucket < Rollups.HOUR) {
            resolution = Resolution.MINUTE;
            bucket = ceilDiv(bucket, Rollups.MINUTE) * Rollups.MINUTE;
        } else {
            resolution = Resolution.HOUR;
            bucket = ceilDiv(bucket, Rollups.HOUR) * Rollups.HOUR;
        }
        long alignedFrom = Math.floorDiv(from, bucket) * bucket;

        List<Sessions.Session> sessions = Sessions.list(c);
        Long resourceId = resourceId(c, ref);
        var series = new ArrayList<Series>();
        for (String metric : metrics) {
            Long seriesId = resourceId == null ? null : seriesId(c, resourceId, metric);
            if (seriesId == null) {
                series.add(new Series(metric, List.of()));
                continue;
            }
            List<Step> steps = switch (resolution) {
                case RAW -> rawSteps(c, seriesId, alignedFrom, to);
                case MINUTE -> withRecentRaw(c, seriesId, rollupSteps(c, "sample_minute", seriesId, alignedFrom, to), to);
                case HOUR -> withRecentRaw(c, seriesId,
                        withRecentRollup(c, seriesId, rollupSteps(c, "sample_hour", seriesId, alignedFrom, to), to), to);
            };
            var points = new ArrayList<Point>();
            for (StepBuckets.Bucket b : StepBuckets.aggregate(steps, alignedFrom, to, bucket)) {
                points.add(new Point(b.start(), Sessions.wallAt(sessions, b.start()),
                        Sessions.at(sessions, b.start()).number(), b.min(), b.max(), b.avg(), b.last()));
            }
            series.add(new Series(metric, points));
        }
        return new History(resolution, alignedFrom, to, bucket, series);
    }

    private static List<Step> rawSteps(Connection c, long seriesId, long from, long to) throws SQLException {
        var steps = new ArrayList<Step>();
        try (var ps = c.prepareStatement("SELECT uptime_ms, value FROM sample WHERE series_id = ? AND uptime_ms ="
                + " (SELECT MAX(uptime_ms) FROM sample WHERE series_id = ? AND uptime_ms < ?)"
                + " UNION ALL SELECT uptime_ms, value FROM sample WHERE series_id = ? AND uptime_ms >= ? AND uptime_ms < ?"
                + " ORDER BY 1")) {
            ps.setLong(1, seriesId);
            ps.setLong(2, seriesId);
            ps.setLong(3, from);
            ps.setLong(4, seriesId);
            ps.setLong(5, from);
            ps.setLong(6, to);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) steps.add(Step.of(rs.getLong(1), rs.getDouble(2)));
            }
        }
        return steps;
    }

    private static List<Step> rollupSteps(Connection c, String table, long seriesId, long from, long to)
            throws SQLException {
        var steps = new ArrayList<Step>();
        try (var ps = c.prepareStatement("SELECT bucket, min, max, avg, last FROM " + table
                + " WHERE series_id = ? AND bucket = (SELECT MAX(bucket) FROM " + table
                + " WHERE series_id = ? AND bucket < ?)"
                + " UNION ALL SELECT bucket, min, max, avg, last FROM " + table
                + " WHERE series_id = ? AND bucket >= ? AND bucket < ? ORDER BY 1")) {
            ps.setLong(1, seriesId);
            ps.setLong(2, seriesId);
            ps.setLong(3, from);
            ps.setLong(4, seriesId);
            ps.setLong(5, from);
            ps.setLong(6, to);
            try (var rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    long bucket = rs.getLong(1);
                    // A carried row before the window only contributes its closing value.
                    if (first && bucket < from) {
                        steps.add(Step.of(bucket, rs.getDouble(5)));
                    } else {
                        steps.add(new Step(bucket, rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
                    }
                    first = false;
                }
            }
        }
        return steps;
    }

    /** Appends minute rollups newer than the last hour row (hours lag behind). */
    private static List<Step> withRecentRollup(Connection c, long seriesId, List<Step> steps, long to)
            throws SQLException {
        long after = steps.isEmpty() ? Long.MIN_VALUE : steps.get(steps.size() - 1).start() + Rollups.HOUR;
        var result = new ArrayList<>(steps);
        for (Step s : rollupSteps(c, "sample_minute", seriesId, after == Long.MIN_VALUE ? 0 : after, to)) {
            if (s.start() >= after) result.add(s);
        }
        return result;
    }

    /** Appends raw samples newer than the last rollup row (rollups lag a minute or two). */
    private static List<Step> withRecentRaw(Connection c, long seriesId, List<Step> steps, long to) throws SQLException {
        long after = steps.isEmpty() ? 0 : steps.get(steps.size() - 1).start() + Rollups.MINUTE;
        var result = new ArrayList<>(steps);
        for (Step s : rawSteps(c, seriesId, after, to)) {
            if (s.start() >= after) result.add(s);
        }
        return result;
    }

    private static Long resourceId(Connection c, ResourceRef ref) throws SQLException {
        try (var ps = c.prepareStatement("SELECT id FROM resource WHERE provider = ? AND type = ? AND rid = ?")) {
            ps.setString(1, ref.provider());
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static Long seriesId(Connection c, long resourceId, String metric) throws SQLException {
        try (var ps = c.prepareStatement("SELECT id FROM series WHERE resource_id = ? AND metric = ?")) {
            ps.setLong(1, resourceId);
            ps.setString(2, metric);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
