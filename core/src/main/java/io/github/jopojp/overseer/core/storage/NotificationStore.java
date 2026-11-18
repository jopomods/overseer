package io.github.jopojp.overseer.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-game chat messages for players (e.g. "Steve asks for access"). The platform delivers
 * them while the player is online and they are deleted on delivery. {@link #hasPending}
 * reads memory only, so the platform can poll it from the server tick. Thread-safe.
 */
public final class NotificationStore implements AutoCloseable {
    private final Connection connection;
    private volatile Set<UUID> pending;

    public NotificationStore(Connection connection) throws SQLException {
        this.connection = connection;
        this.pending = loadPlayers();
    }

    public boolean hasPending(UUID player) {
        return pending.contains(player);
    }

    public synchronized void add(UUID player, String message, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO notification (player, message, created_wall_ms) VALUES (?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, message);
            ps.setLong(3, now.toEpochMilli());
            ps.executeUpdate();
        }
        pending = loadPlayers();
    }

    /** Returns and deletes the player's messages, oldest first. */
    public synchronized List<String> take(UUID player) throws SQLException {
        var messages = new ArrayList<String>();
        try (var ps = connection.prepareStatement(
                "SELECT message FROM notification WHERE player = ? ORDER BY id")) {
            ps.setString(1, player.toString());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) messages.add(rs.getString(1));
            }
        }
        try (var ps = connection.prepareStatement("DELETE FROM notification WHERE player = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
        pending = loadPlayers();
        return messages;
    }

    private Set<UUID> loadPlayers() throws SQLException {
        var players = new HashSet<UUID>();
        try (var st = connection.createStatement();
                var rs = st.executeQuery("SELECT DISTINCT player FROM notification")) {
            while (rs.next()) players.add(UUID.fromString(rs.getString(1)));
        }
        return Set.copyOf(players);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
