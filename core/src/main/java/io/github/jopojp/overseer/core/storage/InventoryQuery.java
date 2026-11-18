package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads inventory changes (ADR-0011). Use a reader connection. */
public final class InventoryQuery {
    private InventoryQuery() {}

    /**
     * Net change of a key since two points on the uptime axis.
     *
     * @param recent change since {@code recentFrom}
     * @param longer change since {@code longerFrom}
     */
    public record Change(String kind, String name, long recent, long longer) {}

    /**
     * Net changes per key since {@code longerFrom}, including keys that are gone now.
     * {@code recentFrom} must not be before {@code longerFrom}. Minute resolution: a bucket
     * counts when any part of it is in the window.
     */
    public static Map<String, Change> changes(Connection c, ResourceRef ref, long recentFrom, long longerFrom)
            throws SQLException {
        var result = new HashMap<String, Change>();
        try (var ps = c.prepareStatement("SELECT k.key, k.kind, k.name,"
                + " SUM(CASE WHEN ch.bucket >= ? THEN ch.delta ELSE 0 END), SUM(ch.delta)"
                + " FROM inventory_change ch JOIN inventory_key k ON k.id = ch.key_id"
                + " WHERE ch.resource_id = (SELECT id FROM resource WHERE provider = ? AND type = ? AND rid = ?)"
                + " AND ch.bucket >= ? GROUP BY ch.key_id")) {
            ps.setLong(1, bucketStart(recentFrom));
            ps.setString(2, ref.provider());
            ps.setString(3, ref.type());
            ps.setString(4, ref.id());
            ps.setLong(5, bucketStart(longerFrom));
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getLong(5) == 0 && rs.getLong(4) == 0) continue;
                    result.put(rs.getString(1), new Change(rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)));
                }
            }
        }
        return result;
    }

    /** Amount of one key at minute resolution: where it started and every minute it changed. */
    public record ItemHistory(long start, List<long[]> points) {}

    /**
     * How the amount of one key moved between {@code from} and {@code to} (uptime). {@code start} is
     * the amount at {@code from}: the current amount minus every change since. Each point is
     * {@code [bucket start, amount at its end]}; between points the amount stayed.
     */
    public static ItemHistory history(Connection c, ResourceRef ref, String key, long from, long to) throws SQLException {
        long fromBucket = bucketStart(from);
        String ids = "(SELECT id FROM resource WHERE provider = ? AND type = ? AND rid = ?)";
        String keyId = "(SELECT id FROM inventory_key WHERE key = ?)";
        long current = 0;
        try (var ps = c.prepareStatement("SELECT amount FROM inventory_amount WHERE resource_id = " + ids
                + " AND key_id = " + keyId)) {
            bind(ps, ref, key);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) current = rs.getLong(1);
            }
        }
        long since = 0;
        try (var ps = c.prepareStatement("SELECT COALESCE(SUM(delta), 0) FROM inventory_change WHERE resource_id = " + ids
                + " AND key_id = " + keyId + " AND bucket >= ?")) {
            bind(ps, ref, key);
            ps.setLong(5, fromBucket);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) since = rs.getLong(1);
            }
        }
        var points = new java.util.ArrayList<long[]>();
        try (var ps = c.prepareStatement("SELECT bucket, amount FROM inventory_change WHERE resource_id = " + ids
                + " AND key_id = " + keyId + " AND bucket >= ? AND bucket <= ? ORDER BY bucket")) {
            bind(ps, ref, key);
            ps.setLong(5, fromBucket);
            ps.setLong(6, to);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) points.add(new long[] {rs.getLong(1), rs.getLong(2)});
            }
        }
        return new ItemHistory(current - since, points);
    }

    private static void bind(java.sql.PreparedStatement ps, ResourceRef ref, String key) throws SQLException {
        ps.setString(1, ref.provider());
        ps.setString(2, ref.type());
        ps.setString(3, ref.id());
        ps.setString(4, key);
    }

    /** The bucket containing {@code t}: a bucket counts when any part of it is in the window. */
    private static long bucketStart(long t) {
        return Math.floorDiv(t, InventoryWriter.BUCKET_MILLIS) * InventoryWriter.BUCKET_MILLIS;
    }
}
