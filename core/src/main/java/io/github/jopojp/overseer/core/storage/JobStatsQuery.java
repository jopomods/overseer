package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistics of the jobs resources ran (ADR-0022), from their recorded attribute events. A resource
 * runs jobs when it reports the attributes {@code job} (stack key, empty while idle), {@code jobKind},
 * {@code jobName} and {@code jobAmount}, like AE2 crafting CPUs. Times are uptime milliseconds, so
 * time the server was down does not count. Use a reader connection.
 */
public final class JobStatsQuery {
    /** Items in one answer at most, most jobs first. */
    public static final int MAX_ITEMS = 200;

    private JobStatsQuery() {}

    /** Busy time and jobs of one resource within the window. */
    public record Busy(ResourceRef resource, long busyMillis, int jobs) {}

    /**
     * One item: jobs that ran within the window, the amount they asked for, their time within the
     * window and the longest finished one.
     */
    public record Item(String key, String kind, String name, int jobs, long amount, long busyMillis, long longestMillis) {}

    public record Stats(List<Busy> resources, List<Item> items) {}

    private static final class Job {
        final String key;
        final long start;
        String kind = "";
        String name = "";
        long amount;

        Job(String key, long start) {
            this.key = key;
            this.start = start;
        }
    }

    private static final class Totals {
        String kind = "";
        String name = "";
        int jobs;
        long amount;
        long busy;
        long longest;
    }

    public static Stats stats(Connection c, List<ResourceRef> refs, long from, long to) throws SQLException {
        var resources = new ArrayList<Busy>();
        var items = new HashMap<String, Totals>();
        for (ResourceRef ref : refs) {
            Long id = resourceId(c, ref);
            if (id == null) {
                resources.add(new Busy(ref, 0, 0));
                continue;
            }
            long[] busy = {0};
            int[] jobs = {0};
            scan(c, id, from, to, (job, end, finished) -> {
                long overlap = Math.min(end, to) - Math.max(job.start, from);
                if (overlap <= 0 && !(job.start >= from && job.start < to)) return;
                busy[0] += Math.max(0, overlap);
                jobs[0]++;
                Totals t = items.computeIfAbsent(job.key, k -> new Totals());
                if (!job.name.isEmpty()) t.name = job.name;
                if (!job.kind.isEmpty()) t.kind = job.kind;
                t.jobs++;
                t.amount += job.amount;
                t.busy += Math.max(0, overlap);
                if (finished) t.longest = Math.max(t.longest, end - job.start);
            });
            resources.add(new Busy(ref, busy[0], jobs[0]));
        }
        var list = new ArrayList<Item>();
        items.forEach((key, t) -> list.add(new Item(key, t.kind, t.name.isEmpty() ? key : t.name, t.jobs, t.amount, t.busy,
                t.longest)));
        list.sort(Comparator.comparingInt(Item::jobs).reversed().thenComparing(Comparator.comparingLong(Item::busyMillis).reversed())
                .thenComparing(Item::key));
        return new Stats(resources, list.size() > MAX_ITEMS ? List.copyOf(list.subList(0, MAX_ITEMS)) : List.copyOf(list));
    }

    private interface Sink {
        void job(Job job, long end, boolean finished);
    }

    /**
     * Replays the job attributes from just before the window (so the job running at {@code from}
     * and the companion values are known) to its end. A job still running at {@code to} ends there.
     */
    private static void scan(Connection c, long resourceId, long from, long to, Sink sink) throws SQLException {
        long startId = 0;
        try (var ps = c.prepareStatement("SELECT MIN(m) FROM (SELECT MAX(id) AS m FROM event WHERE resource_id = ?"
                + " AND kind = 'attribute' AND attribute IN ('job', 'jobAmount', 'jobKind', 'jobName') AND uptime_ms < ?"
                + " GROUP BY attribute)")) {
            ps.setLong(1, resourceId);
            ps.setLong(2, from);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) startId = rs.getLong(1);
            }
        }
        Map<String, String> known = new HashMap<>();
        Job current = null;
        try (var ps = c.prepareStatement("SELECT kind, attribute, value, uptime_ms FROM event WHERE resource_id = ? AND id >= ?"
                + " AND uptime_ms <= ? AND (kind <> 'attribute' OR attribute IN ('job', 'jobAmount', 'jobKind', 'jobName'))"
                + " ORDER BY id")) {
            ps.setLong(1, resourceId);
            ps.setLong(2, startId);
            ps.setLong(3, to);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(1);
                    String attribute = rs.getString(2);
                    String value = rs.getString(3);
                    long at = rs.getLong(4);
                    if (!kind.equals("attribute")) {
                        // A removed resource ends its job; an added one starts idle.
                        if (current != null) sink.job(complete(current, known), at, true);
                        current = null;
                        continue;
                    }
                    known.put(attribute, value == null ? "" : value);
                    if (!attribute.equals("job")) continue;
                    if (current != null) sink.job(complete(current, known), at, true);
                    current = value == null || value.isEmpty() ? null : new Job(value, at);
                }
            }
        }
        if (current != null) sink.job(complete(current, known), to, false);
    }

    /** Companion values come right after the job in the same collection, or stay as they were. */
    private static Job complete(Job job, Map<String, String> known) {
        job.kind = known.getOrDefault("jobKind", "");
        job.name = known.getOrDefault("jobName", "");
        try {
            job.amount = Long.parseLong(known.getOrDefault("jobAmount", "0"));
        } catch (NumberFormatException e) {
            job.amount = 0;
        }
        return job;
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
}
