package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.access.Shares;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shares of resources with single players (ADR-0011), on their own control connection
 * (ADR-0006). Access checks read an in-memory copy that is replaced on every change, so they
 * never touch the database. Thread-safe.
 */
public final class ShareStore implements Shares, AutoCloseable {
    /** A share as shown to the owner. {@code name} is the last known dashboard name. */
    public record Share(UUID player, String name, UUID grantedBy, Instant grantedAt) {}

    private final Connection connection;
    private volatile Map<ResourceRef, Set<UUID>> index;

    public ShareStore(Connection connection) throws SQLException {
        this.connection = connection;
        this.index = load();
    }

    @Override
    public boolean isShared(ResourceRef resource, UUID player) {
        Set<UUID> players = index.get(resource);
        return players != null && players.contains(player);
    }

    public synchronized List<Share> list(ResourceRef ref) throws SQLException {
        var result = new ArrayList<Share>();
        try (var ps = connection.prepareStatement("SELECT s.player, p.name, s.granted_by, s.granted_wall_ms"
                + " FROM resource_share s LEFT JOIN player p ON p.uuid = s.player"
                + " WHERE s.provider = ? AND s.type = ? AND s.rid = ? ORDER BY p.name COLLATE NOCASE, s.player")) {
            bind(ps, ref);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String by = rs.getString(3);
                    result.add(new Share(UUID.fromString(rs.getString(1)), rs.getString(2),
                            by == null ? null : UUID.fromString(by), Instant.ofEpochMilli(rs.getLong(4))));
                }
            }
        }
        return result;
    }

    /** A player who logged in to the dashboard at least once, by name (case-insensitive). */
    public synchronized Optional<UUID> playerByName(String name) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT uuid FROM player WHERE name = ? COLLATE NOCASE ORDER BY last_seen_wall_ms DESC LIMIT 1")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
            }
        }
    }

    public synchronized void add(ResourceRef ref, UUID player, UUID by, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT OR IGNORE INTO resource_share"
                + " (provider, type, rid, player, granted_by, granted_wall_ms) VALUES (?, ?, ?, ?, ?, ?)")) {
            bind(ps, ref);
            ps.setString(4, player.toString());
            ps.setString(5, by == null ? null : by.toString());
            ps.setLong(6, now.toEpochMilli());
            ps.executeUpdate();
        }
        index = load();
    }

    /** @return whether a share existed */
    public synchronized boolean remove(ResourceRef ref, UUID player) throws SQLException {
        int removed;
        try (var ps = connection.prepareStatement(
                "DELETE FROM resource_share WHERE provider = ? AND type = ? AND rid = ? AND player = ?")) {
            bind(ps, ref);
            ps.setString(4, player.toString());
            removed = ps.executeUpdate();
        }
        index = load();
        return removed > 0;
    }

    private Map<ResourceRef, Set<UUID>> load() throws SQLException {
        var map = new HashMap<ResourceRef, Set<UUID>>();
        try (var st = connection.createStatement();
                var rs = st.executeQuery("SELECT provider, type, rid, player FROM resource_share")) {
            while (rs.next()) {
                map.computeIfAbsent(new ResourceRef(rs.getString(1), rs.getString(2), rs.getString(3)),
                        k -> new HashSet<>()).add(UUID.fromString(rs.getString(4)));
            }
        }
        var frozen = new HashMap<ResourceRef, Set<UUID>>();
        map.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        return Map.copyOf(frozen);
    }

    private static void bind(java.sql.PreparedStatement ps, ResourceRef ref) throws SQLException {
        ps.setString(1, ref.provider());
        ps.setString(2, ref.type());
        ps.setString(3, ref.id());
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
