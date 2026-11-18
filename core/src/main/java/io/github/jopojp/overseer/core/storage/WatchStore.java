package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Watched items per resource (ADR-0011). Providers read {@link #keysByResource} on the server
 * thread every collection; it is served from memory. Thread-safe.
 */
public final class WatchStore implements AutoCloseable {
    /** At most this many watched items per resource: each is read every second. */
    public static final int MAX_PER_RESOURCE = 50;

    /**
     * @param item   stack key
     * @param target minimum stock, {@code null} for "just watch"
     */
    public record Watch(ResourceRef resource, String item, String name, String kind, Long target, UUID createdBy,
            Instant createdAt) {}

    private final Connection connection;
    private volatile List<Watch> all;
    private volatile Map<String, Map<String, Set<String>>> keys = Map.of();

    public WatchStore(Connection connection) throws SQLException {
        this.connection = connection;
        reload();
    }

    public List<Watch> list(ResourceRef resource) {
        return all.stream().filter(w -> w.resource().equals(resource)).toList();
    }

    /** Watched stack keys of one provider by resource id, from memory. Cheap. */
    public Map<String, Set<String>> keysByResource(String provider) {
        return keys.getOrDefault(provider, Map.of());
    }

    /** Adds a watch or changes its target. */
    public synchronized void put(ResourceRef ref, String item, String name, String kind, Long target, UUID by,
            Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT INTO watch"
                + " (provider, type, rid, item, name, kind, target, created_by, created_wall_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (provider, type, rid, item)"
                + " DO UPDATE SET target = excluded.target, name = excluded.name")) {
            ps.setString(1, ref.provider());
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            ps.setString(4, item);
            ps.setString(5, name);
            ps.setString(6, kind);
            if (target == null) ps.setNull(7, Types.INTEGER); else ps.setLong(7, target);
            if (by == null) ps.setNull(8, Types.VARCHAR); else ps.setString(8, by.toString());
            ps.setLong(9, now.toEpochMilli());
            ps.executeUpdate();
        }
        reload();
    }

    /** @return whether it was watched */
    public synchronized boolean remove(ResourceRef ref, String item) throws SQLException {
        int removed;
        try (var ps = connection.prepareStatement(
                "DELETE FROM watch WHERE provider = ? AND type = ? AND rid = ? AND item = ?")) {
            ps.setString(1, ref.provider());
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            ps.setString(4, item);
            removed = ps.executeUpdate();
        }
        reload();
        return removed > 0;
    }

    private void reload() throws SQLException {
        var result = new ArrayList<Watch>();
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT provider, type, rid, item, name,"
                + " kind, target, created_by, created_wall_ms FROM watch ORDER BY created_wall_ms, item")) {
            while (rs.next()) {
                long target = rs.getLong(7);
                Long t = rs.wasNull() ? null : target;
                String by = rs.getString(8);
                result.add(new Watch(new ResourceRef(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getString(4),
                        rs.getString(5), rs.getString(6), t, by == null ? null : UUID.fromString(by),
                        Instant.ofEpochMilli(rs.getLong(9))));
            }
        }
        var index = new HashMap<String, Map<String, Set<String>>>();
        for (Watch w : result) {
            index.computeIfAbsent(w.resource().provider(), k -> new HashMap<>())
                    .computeIfAbsent(w.resource().id(), k -> new LinkedHashSet<>()).add(w.item());
        }
        index.replaceAll((p, byId) -> {
            byId.replaceAll((id, set) -> Set.copyOf(set));
            return Map.copyOf(byId);
        });
        all = List.copyOf(result);
        keys = Map.copyOf(index);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
