package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.action.ActionRequest;
import io.github.jopojp.overseer.core.action.ActionResult;
import io.github.jopojp.overseer.core.action.Actor;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The log of world actions (ADR-0016) on its own control connection (ADR-0006). Thread-safe.
 */
public final class ActionLogStore implements AutoCloseable {
    /** One logged action with the values it changed. {@code params} is the request as JSON. */
    public record Entry(long id, Instant at, UUID actor, String actorName, boolean operator, String action,
            ResourceRef target, String params, ActionResult.Status status, String message,
            List<ActionResult.Change> changes) {}

    public static final int MAX_LIMIT = 200;

    private final Connection connection;

    public ActionLogStore(Connection connection) {
        this.connection = connection;
    }

    /** Records one action and its outcome. */
    public synchronized long record(Instant at, Actor actor, ActionRequest request, ActionResult result)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long id;
            try (var ps = connection.prepareStatement("INSERT INTO action_log (at_wall_ms, actor, actor_name, operator,"
                    + " action, provider, type, rid, params, status, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, at.toEpochMilli());
                ps.setString(2, actor.player().toString());
                ps.setString(3, actor.name());
                ps.setInt(4, actor.operator() ? 1 : 0);
                ps.setString(5, request.type());
                bind(ps, 6, request.target());
                ps.setString(9, request.params().toString());
                ps.setString(10, result.status().name());
                ps.setString(11, result.message());
                ps.executeUpdate();
                try (var keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            if (!result.changes().isEmpty()) {
                try (var ps = connection.prepareStatement("INSERT INTO action_change"
                        + " (log_id, provider, type, rid, field, before, after) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (ActionResult.Change change : result.changes()) {
                        ps.setLong(1, id);
                        bind(ps, 2, change.resource());
                        ps.setString(5, change.field());
                        ps.setString(6, change.before());
                        ps.setString(7, change.after());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            connection.commit();
            return id;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Newest entries first.
     *
     * @param resource only actions on this resource or that changed it; {@code null} for all
     * @param beforeId only entries older than this id (paging); {@code null} for the newest
     * @param limit    at most this many, capped at {@link #MAX_LIMIT}
     */
    public synchronized List<Entry> list(ResourceRef resource, Long beforeId, int limit) throws SQLException {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        var sql = new StringBuilder("SELECT id, at_wall_ms, actor, actor_name, operator, action, provider, type, rid,"
                + " params, status, message FROM action_log l WHERE 1 = 1");
        if (resource != null) {
            sql.append(" AND ((provider = ? AND type = ? AND rid = ?) OR id IN (SELECT log_id FROM action_change"
                    + " WHERE provider = ? AND type = ? AND rid = ?))");
        }
        if (beforeId != null) sql.append(" AND id < ?");
        sql.append(" ORDER BY id DESC LIMIT ?");
        var entries = new LinkedHashMap<Long, Entry>();
        try (var ps = connection.prepareStatement(sql.toString())) {
            int i = 1;
            if (resource != null) {
                bind(ps, i, resource);
                bind(ps, i + 3, resource);
                i += 6;
            }
            if (beforeId != null) ps.setLong(i++, beforeId);
            ps.setInt(i, capped);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    entries.put(id, new Entry(id, Instant.ofEpochMilli(rs.getLong(2)), UUID.fromString(rs.getString(3)),
                            rs.getString(4), rs.getInt(5) != 0, rs.getString(6),
                            new ResourceRef(rs.getString(7), rs.getString(8), rs.getString(9)), rs.getString(10),
                            ActionResult.Status.valueOf(rs.getString(11)), rs.getString(12), new ArrayList<>()));
                }
            }
        }
        if (entries.isEmpty()) return List.of();
        Map<Long, List<ActionResult.Change>> changes = new LinkedHashMap<>();
        String ids = String.join(",", entries.keySet().stream().map(String::valueOf).toList());
        try (var st = connection.createStatement();
                var rs = st.executeQuery("SELECT log_id, provider, type, rid, field, before, after FROM action_change"
                        + " WHERE log_id IN (" + ids + ") ORDER BY rowid")) {
            while (rs.next()) {
                changes.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new ActionResult.Change(
                        new ResourceRef(rs.getString(2), rs.getString(3), rs.getString(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7)));
            }
        }
        var result = new ArrayList<Entry>(entries.size());
        for (Entry e : entries.values()) {
            result.add(new Entry(e.id(), e.at(), e.actor(), e.actorName(), e.operator(), e.action(), e.target(),
                    e.params(), e.status(), e.message(), List.copyOf(changes.getOrDefault(e.id(), List.of()))));
        }
        return result;
    }

    private static void bind(PreparedStatement ps, int index, ResourceRef ref) throws SQLException {
        ps.setString(index, ref.provider());
        ps.setString(index + 1, ref.type());
        ps.setString(index + 2, ref.id());
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
