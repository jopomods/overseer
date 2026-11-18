package io.github.jopojp.overseer.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Item icons as PNG by stack key (ADR-0011). Exported icons come from a client and are stored;
 * a platform fallback (e.g. textures in mod jars) fills gaps without being stored. Lookups are
 * cached in memory. Thread-safe.
 */
public final class IconStore implements AutoCloseable {
    /** Icons are 32x32 or 64x64 PNGs; anything larger is not an icon. */
    public static final int MAX_PNG_BYTES = 16 * 1024;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int CACHE_ENTRIES = 4096;

    private final Connection connection;
    private final Function<String, byte[]> fallback;
    private final Map<String, Optional<byte[]>> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<byte[]>> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /** @param fallback icon for a base key when none was exported, or {@code null}; may be slow, is cached */
    public IconStore(Connection connection, Function<String, byte[]> fallback) {
        this.connection = connection;
        this.fallback = fallback == null ? key -> null : fallback;
    }

    /** Whether the bytes look like a PNG icon we accept. */
    public static boolean isPng(byte[] png) {
        return png != null && png.length > PNG_MAGIC.length && png.length <= MAX_PNG_BYTES
                && Arrays.equals(Arrays.copyOf(png, PNG_MAGIC.length), PNG_MAGIC);
    }

    /**
     * The icon of a stack key: exported exact key, exported base key (without NBT hash), then the
     * fallback for the base key.
     */
    public synchronized Optional<byte[]> find(String key) throws SQLException {
        var cached = cache.get(key);
        if (cached != null) return cached;
        String base = base(key);
        byte[] png = load(key);
        if (png == null && !base.equals(key)) png = load(base);
        if (png == null) {
            try {
                png = fallback.apply(base);
            } catch (RuntimeException e) {
                png = null; // a broken fallback means no icon
            }
            if (png != null && !isPng(png)) png = null;
        }
        Optional<byte[]> result = Optional.ofNullable(png);
        cache.put(key, result);
        return result;
    }

    /** Stores exported icons (invalid ones are skipped). @return how many were stored */
    public synchronized int putAll(Map<String, byte[]> icons, UUID by, Instant now) throws SQLException {
        int stored = 0;
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO icon (key, png, uploaded_by, updated_wall_ms)"
                + " VALUES (?, ?, ?, ?)")) {
            for (var e : icons.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 512 || !isPng(e.getValue())) continue;
                ps.setString(1, e.getKey());
                ps.setBytes(2, e.getValue());
                ps.setString(3, by == null ? null : by.toString());
                ps.setLong(4, now.toEpochMilli());
                ps.addBatch();
                stored++;
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
        cache.clear();
        return stored;
    }

    public synchronized long count() throws SQLException {
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM icon")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private byte[] load(String key) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT png FROM icon WHERE key = ?")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** {@code item:minecraft:book#1a2b} -> {@code item:minecraft:book} */
    static String base(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
