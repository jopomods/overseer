package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
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

    /** The bucket containing {@code t}: a bucket counts when any part of it is in the window. */
    private static long bucketStart(long t) {
        return Math.floorDiv(t, InventoryWriter.BUCKET_MILLIS) * InventoryWriter.BUCKET_MILLIS;
    }
}
