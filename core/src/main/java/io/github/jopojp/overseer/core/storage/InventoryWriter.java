package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.Stack;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns full inventory scans into changes (ADR-0011). Part of the {@link StorageWriter}: same
 * thread, same connection, same transaction.
 *
 * <p>Keeps the current amount of every key in memory (loaded at start), so a scan that changes
 * nothing writes nothing, and a restart does not record everything as new. Changes are summed
 * per minute: at most one row per key and minute.
 *
 * <p>The first scan of a resource is a baseline: its contents are stored, not counted as
 * changes. Otherwise installing Overseer would show the whole inventory as gained.
 */
final class InventoryWriter {
    static final long BUCKET_MILLIS = 60_000;

    private record Key(long id, String name) {}

    private final Connection connection;
    private final Map<String, Key> keys = new HashMap<>();
    private final Map<Long, Map<Long, Long>> amounts = new HashMap<>();
    private final Set<Long> baselined = new HashSet<>();
    private PreparedStatement insertKey;
    private PreparedStatement renameKey;
    private PreparedStatement upsertAmount;
    private PreparedStatement deleteAmount;
    private PreparedStatement upsertChange;

    InventoryWriter(Connection connection) {
        this.connection = connection;
    }

    void load() throws SQLException {
        prepare();
        keys.clear();
        amounts.clear();
        baselined.clear();
        try (Statement st = connection.createStatement()) {
            try (var rs = st.executeQuery("SELECT id, key, name FROM inventory_key")) {
                while (rs.next()) keys.put(rs.getString(2), new Key(rs.getLong(1), rs.getString(3)));
            }
            try (var rs = st.executeQuery("SELECT resource_id, key_id, amount FROM inventory_amount")) {
                while (rs.next()) {
                    amounts.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getLong(2), rs.getLong(3));
                }
            }
            try (var rs = st.executeQuery("SELECT resource_id FROM inventory_amount"
                    + " UNION SELECT resource_id FROM inventory_change")) {
                while (rs.next()) baselined.add(rs.getLong(1));
            }
        }
    }

    /** Records the differences between the last known contents and this full scan. */
    void apply(long resourceId, List<Stack> scan, Timestamp at) throws SQLException {
        Map<Long, Long> current = amounts.computeIfAbsent(resourceId, k -> new HashMap<>());
        boolean count = !baselined.add(resourceId);
        var merged = new HashMap<Long, Long>();
        for (Stack stack : scan) {
            if (stack.amount() <= 0) continue;
            merged.merge(keyId(stack), stack.amount(), InventoryWriter::saturatedAdd);
        }
        long bucket = Math.floorDiv(at.uptimeMillis(), BUCKET_MILLIS) * BUCKET_MILLIS;
        for (var entry : merged.entrySet()) {
            long before = current.getOrDefault(entry.getKey(), 0L);
            long now = entry.getValue();
            if (before == now) continue;
            set(resourceId, entry.getKey(), now, at);
            if (count) change(resourceId, bucket, entry.getKey(), now - before, now);
            current.put(entry.getKey(), now);
        }
        Set<Long> gone = new HashSet<>(current.keySet());
        gone.removeAll(merged.keySet());
        for (long keyId : gone) {
            long before = current.remove(keyId);
            deleteAmount.setLong(1, resourceId);
            deleteAmount.setLong(2, keyId);
            deleteAmount.executeUpdate();
            change(resourceId, bucket, keyId, -before, 0);
        }
    }

    /** The id of a key, created on first use; shared with {@link FlowWriter}. */
    long keyId(Stack stack) throws SQLException {
        Key known = keys.get(stack.key());
        if (known != null) {
            if (!known.name().equals(stack.name())) {
                renameKey.setString(1, stack.name());
                renameKey.setLong(2, known.id());
                renameKey.executeUpdate();
                keys.put(stack.key(), new Key(known.id(), stack.name()));
            }
            return known.id();
        }
        insertKey.setString(1, stack.key());
        insertKey.setString(2, stack.kind());
        insertKey.setString(3, stack.name());
        insertKey.executeUpdate();
        long id;
        try (var rs = insertKey.getGeneratedKeys()) {
            if (!rs.next()) throw new SQLException("no generated key");
            id = rs.getLong(1);
        }
        keys.put(stack.key(), new Key(id, stack.name()));
        return id;
    }

    private void set(long resourceId, long keyId, long amount, Timestamp at) throws SQLException {
        upsertAmount.setLong(1, resourceId);
        upsertAmount.setLong(2, keyId);
        upsertAmount.setLong(3, amount);
        upsertAmount.setLong(4, at.uptimeMillis());
        upsertAmount.executeUpdate();
    }

    private void change(long resourceId, long bucket, long keyId, long delta, long amount) throws SQLException {
        upsertChange.setLong(1, resourceId);
        upsertChange.setLong(2, bucket);
        upsertChange.setLong(3, keyId);
        upsertChange.setLong(4, delta);
        upsertChange.setLong(5, amount);
        upsertChange.executeUpdate();
    }

    private void prepare() throws SQLException {
        if (insertKey != null) return;
        insertKey = connection.prepareStatement("INSERT INTO inventory_key (key, kind, name) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
        renameKey = connection.prepareStatement("UPDATE inventory_key SET name = ? WHERE id = ?");
        upsertAmount = connection.prepareStatement("INSERT INTO inventory_amount (resource_id, key_id, amount, uptime_ms)"
                + " VALUES (?, ?, ?, ?) ON CONFLICT (resource_id, key_id) DO UPDATE SET amount = excluded.amount,"
                + " uptime_ms = excluded.uptime_ms");
        deleteAmount = connection.prepareStatement("DELETE FROM inventory_amount WHERE resource_id = ? AND key_id = ?");
        upsertChange = connection.prepareStatement("INSERT INTO inventory_change (resource_id, bucket, key_id, delta, amount)"
                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (resource_id, bucket, key_id) DO UPDATE SET"
                + " delta = delta + excluded.delta, amount = excluded.amount");
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
