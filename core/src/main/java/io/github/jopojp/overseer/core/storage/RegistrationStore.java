package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resources registered in Overseer because the mod does not identify them itself, e.g. AE2
 * grids without a controller (ADR-0011). Providers read {@link #list} from the server thread
 * every collection; it is served from memory. Thread-safe.
 */
public final class RegistrationStore implements AutoCloseable {
    public record Registration(ResourceRef resource, UUID owner, Instant createdAt) {}

    private final Connection connection;
    private volatile List<Registration> all;

    public RegistrationStore(Connection connection) throws SQLException {
        this.connection = connection;
        this.all = load();
    }

    /** Registrations of one provider, from memory. */
    public List<Registration> list(String provider) {
        return all.stream().filter(r -> r.resource().provider().equals(provider)).toList();
    }

    public Optional<Registration> find(ResourceRef ref) {
        return all.stream().filter(r -> r.resource().equals(ref)).findFirst();
    }

    /** Registers or re-registers (new owner) a resource. */
    public synchronized void put(ResourceRef ref, UUID owner, Instant now) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO registration"
                + " (provider, type, rid, owner, created_wall_ms) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, ref.provider());
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            ps.setString(4, owner.toString());
            ps.setLong(5, now.toEpochMilli());
            ps.executeUpdate();
        }
        all = load();
    }

    /** @return whether it was registered */
    public synchronized boolean remove(ResourceRef ref) throws SQLException {
        int removed;
        try (var ps = connection.prepareStatement(
                "DELETE FROM registration WHERE provider = ? AND type = ? AND rid = ?")) {
            ps.setString(1, ref.provider());
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            removed = ps.executeUpdate();
        }
        all = load();
        return removed > 0;
    }

    private List<Registration> load() throws SQLException {
        var result = new ArrayList<Registration>();
        try (var st = connection.createStatement();
                var rs = st.executeQuery("SELECT provider, type, rid, owner, created_wall_ms FROM registration")) {
            while (rs.next()) {
                result.add(new Registration(new ResourceRef(rs.getString(1), rs.getString(2), rs.getString(3)),
                        UUID.fromString(rs.getString(4)), Instant.ofEpochMilli(rs.getLong(5))));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
