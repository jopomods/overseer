package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.provider.Flow;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds flows to their minute (ADR-0020). Part of the {@link StorageWriter}: same thread, same
 * connection, same transaction. Keys are shared with inventories ({@link InventoryWriter}).
 */
final class FlowWriter {
    private record Device(long id, String kind, String name) {}

    private final Connection connection;
    private final InventoryWriter keys;
    private final Map<String, Device> devices = new HashMap<>();
    private PreparedStatement insertDevice;
    private PreparedStatement updateDevice;
    private PreparedStatement upsertMinute;

    FlowWriter(Connection connection, InventoryWriter keys) {
        this.connection = connection;
        this.keys = keys;
    }

    void load() throws SQLException {
        prepare();
        devices.clear();
        try (Statement st = connection.createStatement();
                var rs = st.executeQuery("SELECT id, resource_id, device, kind, name FROM flow_device")) {
            while (rs.next()) {
                devices.put(rs.getLong(2) + "|" + rs.getString(3), new Device(rs.getLong(1), rs.getString(4), rs.getString(5)));
            }
        }
    }

    /** Adds what the devices of one network moved to the current minute. */
    void apply(long resourceId, List<Flow> flows, Timestamp at) throws SQLException {
        long bucket = Math.floorDiv(at.uptimeMillis(), InventoryWriter.BUCKET_MILLIS) * InventoryWriter.BUCKET_MILLIS;
        for (Flow flow : flows) {
            if (flow.inserted() == 0 && flow.extracted() == 0) continue;
            upsertMinute.setLong(1, deviceId(resourceId, flow));
            upsertMinute.setLong(2, bucket);
            upsertMinute.setLong(3, keys.keyId(flow.item()));
            upsertMinute.setLong(4, flow.inserted());
            upsertMinute.setLong(5, flow.extracted());
            upsertMinute.executeUpdate();
        }
    }

    private long deviceId(long resourceId, Flow flow) throws SQLException {
        String key = resourceId + "|" + flow.device();
        Device known = devices.get(key);
        if (known != null) {
            if (!known.kind().equals(flow.deviceKind()) || !known.name().equals(flow.deviceName())) {
                updateDevice.setString(1, flow.deviceKind());
                updateDevice.setString(2, flow.deviceName());
                updateDevice.setLong(3, known.id());
                updateDevice.executeUpdate();
                devices.put(key, new Device(known.id(), flow.deviceKind(), flow.deviceName()));
            }
            return known.id();
        }
        insertDevice.setLong(1, resourceId);
        insertDevice.setString(2, flow.device());
        insertDevice.setString(3, flow.deviceKind());
        insertDevice.setString(4, flow.deviceName());
        insertDevice.executeUpdate();
        long id;
        try (var rs = insertDevice.getGeneratedKeys()) {
            if (!rs.next()) throw new SQLException("no generated key");
            id = rs.getLong(1);
        }
        devices.put(key, new Device(id, flow.deviceKind(), flow.deviceName()));
        return id;
    }

    private void prepare() throws SQLException {
        if (insertDevice != null) return;
        insertDevice = connection.prepareStatement("INSERT INTO flow_device (resource_id, device, kind, name) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
        updateDevice = connection.prepareStatement("UPDATE flow_device SET kind = ?, name = ? WHERE id = ?");
        upsertMinute = connection.prepareStatement("INSERT INTO flow_minute (device_id, bucket, key_id, inserted, extracted)"
                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (device_id, bucket, key_id) DO UPDATE SET"
                + " inserted = inserted + excluded.inserted, extracted = extracted + excluded.extracted");
    }
}
