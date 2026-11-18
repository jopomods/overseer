package io.github.jopojp.overseer.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Whether the tick profiler runs (ADR-0014). Admins start it from the dashboard for a duration or
 * keep it running; the platform reads {@link #current} every collection. Kept in {@code meta}, so a
 * continuous profiler survives a restart and a timed one ends on time. Thread-safe.
 */
public final class ProfilerStore implements AutoCloseable {
    /** How long and how often to measure. */
    public record Settings(Mode mode, long untilWallMs, int intervalSeconds) {
        public static final Settings OFF = new Settings(Mode.OFF, 0, 10);

        public Settings {
            if (intervalSeconds < MIN_INTERVAL || intervalSeconds > MAX_INTERVAL) {
                throw new IllegalArgumentException("interval must be " + MIN_INTERVAL + " to " + MAX_INTERVAL + " s");
            }
        }

        /** Whether it measures at this moment. */
        public boolean active(Instant now) {
            return mode == Mode.CONTINUOUS || (mode == Mode.TIMED && now.toEpochMilli() < untilWallMs);
        }
    }

    public enum Mode { OFF, TIMED, CONTINUOUS }

    public static final int MIN_INTERVAL = 5;
    public static final int MAX_INTERVAL = 300;
    /** A timed run lasts at most a day; longer means "continuous". */
    public static final long MAX_DURATION_SECONDS = 86_400;

    private static final String KEY = "profiler";
    private final Connection connection;
    private volatile Settings current;

    public ProfilerStore(Connection connection) throws SQLException {
        this.connection = connection;
        this.current = load();
    }

    public Settings current() {
        return current;
    }

    public synchronized void set(Settings settings) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, KEY);
            ps.setString(2, settings.mode().name() + ";" + settings.untilWallMs() + ";" + settings.intervalSeconds());
            ps.executeUpdate();
        }
        current = settings;
    }

    private Settings load() throws SQLException {
        try (var ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, KEY);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Settings.OFF;
                String[] parts = rs.getString(1).split(";");
                return new Settings(Mode.valueOf(parts[0]), Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
            }
        } catch (RuntimeException e) {
            return Settings.OFF; // unreadable: off is the safe default
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
