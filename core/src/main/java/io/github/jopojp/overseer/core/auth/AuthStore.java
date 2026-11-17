package io.github.jopojp.overseer.core.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent auth state: players, dashboard admins, sessions and device tokens
 * (ADR-0007). Only token hashes are stored. All methods are synchronized on this
 * store, which owns its control-writer connection.
 */
public final class AuthStore implements AutoCloseable {
    public enum Kind {
        SESSION("session"),
        DEVICE_TOKEN("device_token");

        final String db;

        Kind(String db) {
            this.db = db;
        }

        static Kind of(String db) {
            return db.equals(SESSION.db) ? SESSION : DEVICE_TOKEN;
        }

        public String json() {
            return db;
        }
    }

    /** A newly issued credential; {@code token} exists only here, never in storage. */
    public record Issued(long id, String token) {}

    /** A valid credential as resolved from a token. */
    public record Resolved(long id, Kind kind, PlayerRef player) {}

    /** Listing entry for "my sessions and tokens". */
    public record Info(long id, Kind kind, String name, Instant createdAt, Instant lastUsedAt) {}

    /** Last-used timestamps are only rewritten after this much time, to avoid a write per request. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final Connection connection;
    private final Duration sessionIdleTimeout;

    public AuthStore(Connection connection, Duration sessionIdleTimeout) {
        this.connection = connection;
        this.sessionIdleTimeout = sessionIdleTimeout;
    }

    public synchronized void upsertPlayer(PlayerRef player, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT INTO player (uuid, name, last_seen_wall_ms) VALUES (?, ?, ?)"
                + " ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, last_seen_wall_ms = excluded.last_seen_wall_ms")) {
            ps.setString(1, player.uuid().toString());
            ps.setString(2, player.name());
            ps.setLong(3, now.toEpochMilli());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<PlayerRef> findPlayer(UUID uuid) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT name FROM player WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new PlayerRef(uuid, rs.getString(1))) : Optional.empty();
            }
        }
    }

    public synchronized Optional<PlayerRef> findPlayerByName(String name) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT uuid, name FROM player WHERE name = ? COLLATE NOCASE ORDER BY last_seen_wall_ms DESC LIMIT 1")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)))
                        : Optional.empty();
            }
        }
    }

    public synchronized Issued issue(Kind kind, UUID player, String name, Instant now) throws SQLException {
        String token = Secrets.newToken();
        try (var ps = connection.prepareStatement("INSERT INTO credential"
                + " (kind, token_hash, player_uuid, name, created_wall_ms, last_used_wall_ms) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind.db);
            ps.setString(2, Secrets.sha256(token));
            ps.setString(3, player.toString());
            ps.setString(4, name);
            ps.setLong(5, now.toEpochMilli());
            ps.setLong(6, now.toEpochMilli());
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Issued(keys.getLong(1), token);
            }
        }
    }

    /**
     * Resolves a token to a valid credential. Sessions expire after the idle timeout
     * since last use (sliding); device tokens never expire. Updates last-used.
     */
    public synchronized Optional<Resolved> resolve(String token, Instant now) throws SQLException {
        if (token == null || token.isEmpty()) return Optional.empty();
        try (var ps = connection.prepareStatement("SELECT c.id, c.kind, c.player_uuid, c.last_used_wall_ms, p.name"
                + " FROM credential c LEFT JOIN player p ON p.uuid = c.player_uuid"
                + " WHERE c.token_hash = ? AND c.revoked_wall_ms IS NULL")) {
            ps.setString(1, Secrets.sha256(token));
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long id = rs.getLong(1);
                Kind kind = Kind.of(rs.getString(2));
                UUID uuid = UUID.fromString(rs.getString(3));
                Instant lastUsed = Instant.ofEpochMilli(rs.getLong(4));
                String name = rs.getString(5) != null ? rs.getString(5) : uuid.toString();
                if (kind == Kind.SESSION && !now.isBefore(lastUsed.plus(sessionIdleTimeout))) return Optional.empty();
                if (!now.isBefore(lastUsed.plus(TOUCH_INTERVAL))) touch(id, now);
                return Optional.of(new Resolved(id, kind, new PlayerRef(uuid, name)));
            }
        }
    }

    /** Whether a credential is still usable (for long-lived SSE connections). */
    public synchronized boolean isValid(long id, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT kind, last_used_wall_ms FROM credential WHERE id = ? AND revoked_wall_ms IS NULL")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                if (Kind.of(rs.getString(1)) == Kind.DEVICE_TOKEN) return true;
                return now.isBefore(Instant.ofEpochMilli(rs.getLong(2)).plus(sessionIdleTimeout));
            }
        }
    }

    public synchronized List<Info> list(UUID player, Instant now) throws SQLException {
        var result = new ArrayList<Info>();
        try (var ps = connection.prepareStatement("SELECT id, kind, name, created_wall_ms, last_used_wall_ms"
                + " FROM credential WHERE player_uuid = ? AND revoked_wall_ms IS NULL ORDER BY last_used_wall_ms DESC")) {
            ps.setString(1, player.toString());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    Kind kind = Kind.of(rs.getString(2));
                    Instant lastUsed = Instant.ofEpochMilli(rs.getLong(5));
                    if (kind == Kind.SESSION && !now.isBefore(lastUsed.plus(sessionIdleTimeout))) continue;
                    result.add(new Info(rs.getLong(1), kind, rs.getString(3),
                            Instant.ofEpochMilli(rs.getLong(4)), lastUsed));
                }
            }
        }
        return result;
    }

    /** Revokes one credential of {@code owner}; {@code false} if it is not theirs or unknown. */
    public synchronized boolean revoke(long id, UUID owner, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("UPDATE credential SET revoked_wall_ms = ?"
                + " WHERE id = ? AND player_uuid = ? AND revoked_wall_ms IS NULL")) {
            ps.setLong(1, now.toEpochMilli());
            ps.setLong(2, id);
            ps.setString(3, owner.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Revokes every credential of the player of the given kind. Returns the count. */
    public synchronized int revokeAll(UUID player, Kind kind, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("UPDATE credential SET revoked_wall_ms = ?"
                + " WHERE player_uuid = ? AND kind = ? AND revoked_wall_ms IS NULL")) {
            ps.setLong(1, now.toEpochMilli());
            ps.setString(2, player.toString());
            ps.setString(3, kind.db);
            return ps.executeUpdate();
        }
    }

    public synchronized boolean isDashboardAdmin(UUID player) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT 1 FROM dashboard_admin WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized boolean grantAdmin(UUID player, String grantedBy, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO dashboard_admin (uuid, granted_by, granted_wall_ms) VALUES (?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, grantedBy);
            ps.setLong(3, now.toEpochMilli());
            return ps.executeUpdate() == 1;
        }
    }

    public synchronized boolean revokeAdmin(UUID player) throws SQLException {
        try (var ps = connection.prepareStatement("DELETE FROM dashboard_admin WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            return ps.executeUpdate() == 1;
        }
    }

    private void touch(long id, Instant now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE credential SET last_used_wall_ms = ? WHERE id = ?")) {
            ps.setLong(1, now.toEpochMilli());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
