package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Access requests for shareable resources (ADR-0011), on their own control connection
 * (ADR-0006). Thread-safe.
 *
 * <p>Players who may not see a resource only learn an opaque <em>handle</em> for it, never
 * its id: ids contain block positions, and those would reveal other players' bases. Handles
 * are an HMAC of the resource key with a per-server secret, stable across restarts.
 */
public final class AccessRequestStore implements AutoCloseable {
    /** After a denial the same player may ask again only after this. */
    public static final Duration DENY_COOL_DOWN = Duration.ofHours(24);

    public enum Status { NONE, PENDING, DENIED }

    public enum Outcome { CREATED, ALREADY_PENDING, DENIED_RECENTLY }

    public record Pending(ResourceRef resource, UUID player, String name, Instant requestedAt) {}

    private static final String SECRET_KEY = "access_handle_secret";

    private final Connection connection;
    private final SecretKeySpec secret;

    public AccessRequestStore(Connection connection) throws SQLException {
        this.connection = connection;
        this.secret = new SecretKeySpec(loadSecret(), "HmacSHA256");
    }

    /** Opaque, stable handle of a resource for players who cannot see it. */
    public String handle(ResourceRef ref) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            byte[] digest = mac.doFinal(ref.key().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 16));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Status status(ResourceRef ref, UUID player, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT denied_wall_ms FROM access_request"
                + " WHERE provider = ? AND type = ? AND rid = ? AND player = ?")) {
            bind(ps, ref, player);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Status.NONE;
                long denied = rs.getLong(1);
                if (rs.wasNull()) return Status.PENDING;
                return now.toEpochMilli() - denied < DENY_COOL_DOWN.toMillis() ? Status.DENIED : Status.NONE;
            }
        }
    }

    public synchronized Outcome request(ResourceRef ref, UUID player, Instant now) throws SQLException {
        Status status = status(ref, player, now);
        if (status == Status.PENDING) return Outcome.ALREADY_PENDING;
        if (status == Status.DENIED) return Outcome.DENIED_RECENTLY;
        try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO access_request"
                + " (provider, type, rid, player, requested_wall_ms, denied_wall_ms) VALUES (?, ?, ?, ?, ?, NULL)")) {
            bind(ps, ref, player);
            ps.setLong(5, now.toEpochMilli());
            ps.executeUpdate();
        }
        return Outcome.CREATED;
    }

    /** All pending requests; the caller filters by what the viewer may manage. */
    public synchronized List<Pending> pending() throws SQLException {
        var result = new ArrayList<Pending>();
        try (var st = connection.createStatement();
                var rs = st.executeQuery("SELECT r.provider, r.type, r.rid, r.player, p.name, r.requested_wall_ms"
                        + " FROM access_request r LEFT JOIN player p ON p.uuid = r.player"
                        + " WHERE r.denied_wall_ms IS NULL ORDER BY r.requested_wall_ms")) {
            while (rs.next()) {
                result.add(new Pending(new ResourceRef(rs.getString(1), rs.getString(2), rs.getString(3)),
                        UUID.fromString(rs.getString(4)), rs.getString(5), Instant.ofEpochMilli(rs.getLong(6))));
            }
        }
        return result;
    }

    public synchronized Optional<Pending> find(ResourceRef ref, UUID player) throws SQLException {
        return pending().stream().filter(p -> p.resource().equals(ref) && p.player().equals(player)).findFirst();
    }

    /** Approved requests disappear (a share replaces them). */
    public synchronized void approve(ResourceRef ref, UUID player) throws SQLException {
        try (var ps = connection.prepareStatement(
                "DELETE FROM access_request WHERE provider = ? AND type = ? AND rid = ? AND player = ?")) {
            bind(ps, ref, player);
            ps.executeUpdate();
        }
    }

    /** Denied requests stay for the cool-down. */
    public synchronized void deny(ResourceRef ref, UUID player, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("UPDATE access_request SET denied_wall_ms = ?"
                + " WHERE provider = ? AND type = ? AND rid = ? AND player = ?")) {
            ps.setLong(1, now.toEpochMilli());
            ps.setString(2, ref.provider());
            ps.setString(3, ref.type());
            ps.setString(4, ref.id());
            ps.setString(5, player.toString());
            ps.executeUpdate();
        }
    }

    /** The per-server secret, created on first use. */
    private byte[] loadSecret() throws SQLException {
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        try (var ps = connection.prepareStatement("INSERT OR IGNORE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, SECRET_KEY);
            ps.setString(2, HexFormat.of().formatHex(fresh));
            ps.executeUpdate();
        }
        try (var ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, SECRET_KEY);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return HexFormat.of().parseHex(rs.getString(1));
            }
        }
    }

    private static void bind(PreparedStatement ps, ResourceRef ref, UUID player) throws SQLException {
        ps.setString(1, ref.provider());
        ps.setString(2, ref.type());
        ps.setString(3, ref.id());
        ps.setString(4, player.toString());
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
