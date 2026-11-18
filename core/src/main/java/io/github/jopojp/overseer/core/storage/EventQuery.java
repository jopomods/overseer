package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads recorded events of a resource, newest first (ADR-0005). Use a reader connection. */
public final class EventQuery {
    private EventQuery() {}

    /**
     * One recorded change.
     *
     * @param kind      {@code added}, {@code removed} or {@code attribute}
     * @param attribute changed attribute for kind {@code attribute}, else {@code null}
     * @param value     new value; {@code null} when removed
     */
    public record Event(Timestamp at, String kind, String attribute, String value) {}

    /**
     * Events of one resource, newest first.
     *
     * @param attributes only attribute changes of these attributes (added/removed always); empty = all
     * @param limit      at most this many
     */
    public static List<Event> events(Connection c, ResourceRef ref, List<String> attributes, int limit)
            throws SQLException {
        String filter = attributes.isEmpty() ? ""
                : " AND (e.kind <> 'attribute' OR e.attribute IN (" + String.join(", ", Collections.nCopies(attributes.size(), "?")) + "))";
        var result = new ArrayList<Event>();
        try (var ps = c.prepareStatement("SELECT e.kind, e.attribute, e.value, e.wall_ms, e.uptime_ms, e.session"
                + " FROM event e JOIN resource r ON r.id = e.resource_id"
                + " WHERE r.provider = ? AND r.type = ? AND r.rid = ?" + filter
                + " ORDER BY e.id DESC LIMIT ?")) {
            int i = 1;
            ps.setString(i++, ref.provider());
            ps.setString(i++, ref.type());
            ps.setString(i++, ref.id());
            for (String attribute : attributes) ps.setString(i++, attribute);
            ps.setInt(i, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var at = new Timestamp(Instant.ofEpochMilli(rs.getLong(4)), rs.getLong(5), rs.getInt(6));
                    result.add(new Event(at, rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return result;
    }
}
