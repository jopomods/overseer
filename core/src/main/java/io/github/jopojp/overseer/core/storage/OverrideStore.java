package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Overseer-side settings of resources, set in the UI (e.g. the zone of a Flux device).
 * Low-volume control writes on their own connection (ADR-0006). Thread-safe.
 */
public final class OverrideStore implements AutoCloseable {
    /** Keys that may be set through the API. */
    public static final List<String> KEYS = List.of("zone");

    public record Entry(ResourceRef resource, String key, String value) {}

    private final Connection connection;

    public OverrideStore(Connection connection) {
        this.connection = connection;
    }

    public synchronized List<Entry> list(String provider) throws SQLException {
        var result = new ArrayList<Entry>();
        try (var ps = connection.prepareStatement(
                "SELECT type, rid, key, value FROM resource_override WHERE provider = ? ORDER BY type, rid, key")) {
            ps.setString(1, provider);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Entry(new ResourceRef(provider, rs.getString(1), rs.getString(2)), rs.getString(3),
                            rs.getString(4)));
                }
            }
        }
        return result;
    }

    /** Sets or, with {@code value == null}, removes an override. */
    public synchronized void set(ResourceRef ref, String key, String value, String by, Instant now) throws SQLException {
        if (value == null) {
            try (var ps = connection.prepareStatement(
                    "DELETE FROM resource_override WHERE provider = ? AND type = ? AND rid = ? AND key = ?")) {
                ps.setString(1, ref.provider());
                ps.setString(2, ref.type());
                ps.setString(3, ref.id());
                ps.setString(4, key);
                ps.executeUpdate();
            }
            return;
        }
        try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO resource_override"
                + " (provider, type, rid, key, value, updated_by, updated_wall_ms) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, ref.provider());
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            ps.setString(4, key);
            ps.setString(5, value);
            ps.setString(6, by);
            ps.setLong(7, now.toEpochMilli());
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
