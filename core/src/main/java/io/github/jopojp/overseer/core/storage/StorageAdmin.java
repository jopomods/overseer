package io.github.jopojp.overseer.core.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage administration (ADR-0006): sizes, retention settings, purge, VACUUM, backup.
 * Write operations run on the storage writer thread via {@link StorageWriter#submit}.
 */
public final class StorageAdmin {
    static final String RAW = "retention.raw";
    static final String MINUTE = "retention.minute";
    static final String HOUR = "retention.hour";
    static final String WARN = "storage.warnBytes";

    /** Default size warning: 2 GiB. */
    public static final long DEFAULT_WARN_BYTES = 2L * 1024 * 1024 * 1024;

    private StorageAdmin() {}

    /** @param inventoryChanges rows of per-minute inventory changes (ADR-0011) */
    public record ProviderUsage(String provider, long series, long raw, long minute, long hour, long inventoryChanges) {}

    public record Usage(long fileBytes, long walBytes, List<ProviderUsage> providers, long icons, long iconBytes) {}

    /** Admin-editable settings; retention in server-uptime milliseconds, 0 = forever. */
    public record Settings(long rawMillis, long minuteMillis, long hourMillis, long warnBytes) {
        public Settings {
            if (rawMillis < 0 || minuteMillis < 0 || hourMillis < 0 || warnBytes < 0) {
                throw new IllegalArgumentException("values must be >= 0");
            }
        }
    }

    public static Usage usage(Connection reader, Path file) throws SQLException {
        var providers = new ArrayList<ProviderUsage>();
        try (var st = reader.createStatement(); var rs = st.executeQuery("SELECT r.provider, COUNT(DISTINCT s.id),"
                + " (SELECT COUNT(*) FROM sample x JOIN series y ON y.id = x.series_id JOIN resource z ON z.id = y.resource_id WHERE z.provider = r.provider),"
                + " (SELECT COUNT(*) FROM sample_minute x JOIN series y ON y.id = x.series_id JOIN resource z ON z.id = y.resource_id WHERE z.provider = r.provider),"
                + " (SELECT COUNT(*) FROM sample_hour x JOIN series y ON y.id = x.series_id JOIN resource z ON z.id = y.resource_id WHERE z.provider = r.provider),"
                + " (SELECT COUNT(*) FROM inventory_change x JOIN resource z ON z.id = x.resource_id WHERE z.provider = r.provider)"
                + " + (SELECT COUNT(*) FROM flow_minute x JOIN flow_device y ON y.id = x.device_id"
                + " JOIN resource z ON z.id = y.resource_id WHERE z.provider = r.provider)"
                + " FROM resource r LEFT JOIN series s ON s.resource_id = r.id GROUP BY r.provider ORDER BY r.provider")) {
            while (rs.next()) {
                providers.add(new ProviderUsage(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6)));
            }
        }
        long icons = 0;
        long iconBytes = 0;
        try (var st = reader.createStatement(); var rs = st.executeQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(png)), 0) FROM icon")) {
            if (rs.next()) {
                icons = rs.getLong(1);
                iconBytes = rs.getLong(2);
            }
        }
        return new Usage(size(file), size(Path.of(file + "-wal")), providers, icons, iconBytes);
    }

    /** Settings stored in {@code meta}, falling back to the given defaults. */
    public static Settings load(Connection c, StorageSettings defaults) throws SQLException {
        return new Settings(
                meta(c, RAW, defaults.rawRetentionMillis()),
                meta(c, MINUTE, defaults.minuteRetentionMillis()),
                meta(c, HOUR, defaults.hourRetentionMillis()),
                meta(c, WARN, DEFAULT_WARN_BYTES));
    }

    public static void save(Connection c, Settings s) throws SQLException {
        setMeta(c, RAW, s.rawMillis());
        setMeta(c, MINUTE, s.minuteMillis());
        setMeta(c, HOUR, s.hourMillis());
        setMeta(c, WARN, s.warnBytes());
        c.commit();
    }

    public static StorageSettings apply(StorageSettings base, Settings s) {
        return new StorageSettings(base.batchSize(), base.heartbeatMillis(), base.sessionFlushMillis(),
                base.rollupIntervalMillis(), s.rawMillis(), s.minuteMillis(), s.hourMillis());
    }

    /**
     * Deletes history (raw samples and rollups) of a provider's series. Resources and events
     * are kept. With {@code beforeUptime} only older data is deleted. Returns deleted rows.
     */
    public static long purge(Connection c, String provider, Long beforeUptime) throws SQLException {
        long deleted = 0;
        String series = "SELECT s.id FROM series s JOIN resource r ON r.id = s.resource_id WHERE r.provider = ?";
        String[][] tables = {{"sample", "uptime_ms"}, {"sample_minute", "bucket"}, {"sample_hour", "bucket"}};
        for (String[] t : tables) {
            String sql = "DELETE FROM " + t[0] + " WHERE series_id IN (" + series + ")"
                    + (beforeUptime == null ? "" : " AND " + t[1] + " < ?");
            try (var ps = c.prepareStatement(sql)) {
                ps.setString(1, provider);
                if (beforeUptime != null) ps.setLong(2, beforeUptime);
                deleted += ps.executeUpdate();
            }
        }
        // Flows of the provider's networks go too (ADR-0020).
        try (var ps = c.prepareStatement("DELETE FROM flow_minute WHERE device_id IN (SELECT d.id FROM flow_device d"
                + " JOIN resource r ON r.id = d.resource_id WHERE r.provider = ?)" + (beforeUptime == null ? "" : " AND bucket < ?"))) {
            ps.setString(1, provider);
            if (beforeUptime != null) ps.setLong(2, beforeUptime);
            deleted += ps.executeUpdate();
        }
        // Inventory changes of the provider's resources go too; current amounts stay.
        try (var ps = c.prepareStatement("DELETE FROM inventory_change WHERE resource_id IN"
                + " (SELECT id FROM resource WHERE provider = ?)" + (beforeUptime == null ? "" : " AND bucket < ?"))) {
            ps.setString(1, provider);
            if (beforeUptime != null) ps.setLong(2, beforeUptime);
            deleted += ps.executeUpdate();
        }
        c.commit();
        return deleted;
    }

    /**
     * Rebuilds the file to release free pages. Runs on a fresh connection: the writer's cached
     * statements would block it ("SQL statements in progress"). Call from the writer thread so
     * nothing is written meanwhile.
     */
    public static void vacuum(Database db) throws SQLException {
        try (var c = db.openControlWriter(); var st = c.createStatement()) {
            st.execute("VACUUM");
        }
    }

    /** Writes a consistent, compacted copy of the database to {@code target} (must not exist). */
    public static void backup(Database db, Path target) throws SQLException {
        try (var c = db.openReader(); var st = c.createStatement()) {
            st.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }

    private static long size(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0;
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private static long meta(Connection c, String key, long fallback) throws SQLException {
        try (var ps = c.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Long.parseLong(rs.getString(1)) : fallback;
            }
        }
    }

    private static void setMeta(Connection c, String key, long value) throws SQLException {
        try (var ps = c.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, Long.toString(value));
            ps.executeUpdate();
        }
    }
}
