package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Reads flows (ADR-0020). Use a reader connection. */
public final class FlowQuery {
    /** Rows one query returns at most; the largest flows win. */
    public static final int MAX_ROWS = 5_000;

    private FlowQuery() {}

    /** What one device moved of one key in the window. */
    public record Row(String device, String deviceKind, String deviceName, String key, String kind, String name,
            long inserted, long extracted) {}

    /**
     * Totals per device and key of a network between {@code from} and {@code to} (uptime, minute
     * resolution: a minute counts when it starts in the window), largest first.
     */
    public static List<Row> totals(Connection c, ResourceRef network, long from, long to) throws SQLException {
        var rows = new ArrayList<Row>();
        try (var ps = c.prepareStatement("SELECT d.device, d.kind, d.name, k.key, k.kind, k.name,"
                + " SUM(m.inserted) AS ins, SUM(m.extracted) AS ext"
                + " FROM flow_minute m JOIN flow_device d ON d.id = m.device_id JOIN inventory_key k ON k.id = m.key_id"
                + " WHERE d.resource_id = (SELECT id FROM resource WHERE provider = ? AND type = ? AND rid = ?)"
                + " AND m.bucket >= ? AND m.bucket <= ?"
                + " GROUP BY m.device_id, m.key_id ORDER BY ins + ext DESC LIMIT ?")) {
            ps.setString(1, network.provider());
            ps.setString(2, network.type());
            ps.setString(3, network.id());
            ps.setLong(4, Math.floorDiv(from, InventoryWriter.BUCKET_MILLIS) * InventoryWriter.BUCKET_MILLIS);
            ps.setLong(5, to);
            ps.setInt(6, MAX_ROWS);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getLong(7), rs.getLong(8)));
                }
            }
        }
        return rows;
    }

    /** Per minute in and out of one key (all devices) or one device (all keys); either may be null. */
    public static List<long[]> perMinute(Connection c, ResourceRef network, String key, String device, long from, long to)
            throws SQLException {
        var points = new ArrayList<long[]>();
        try (var ps = c.prepareStatement("SELECT m.bucket, SUM(m.inserted), SUM(m.extracted)"
                + " FROM flow_minute m JOIN flow_device d ON d.id = m.device_id JOIN inventory_key k ON k.id = m.key_id"
                + " WHERE d.resource_id = (SELECT id FROM resource WHERE provider = ? AND type = ? AND rid = ?)"
                + " AND m.bucket >= ? AND m.bucket <= ?"
                + (key == null ? "" : " AND k.key = ?") + (device == null ? "" : " AND d.device = ?")
                + " GROUP BY m.bucket ORDER BY m.bucket")) {
            int i = 1;
            ps.setString(i++, network.provider());
            ps.setString(i++, network.type());
            ps.setString(i++, network.id());
            ps.setLong(i++, Math.floorDiv(from, InventoryWriter.BUCKET_MILLIS) * InventoryWriter.BUCKET_MILLIS);
            ps.setLong(i++, to);
            if (key != null) ps.setString(i++, key);
            if (device != null) ps.setString(i, device);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) points.add(new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
            }
        }
        return points;
    }
}
