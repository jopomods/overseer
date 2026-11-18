package io.github.jopojp.overseer.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.sqlite.SQLiteConfig;

/**
 * The SQLite database file (ADR-0006).
 *
 * <p>Owns the single writer connection; only the storage writer thread (and startup code
 * before it starts) may use it. Readers open their own read-only connections.
 *
 * <p>Connections are created through {@link SQLiteConfig} directly rather than
 * {@code DriverManager}, which does not reliably find drivers across Forge's module layers.
 */
public final class Database implements AutoCloseable {
    /** Applied in order; index + 1 is the schema version stored in {@code PRAGMA user_version}. */
    static final List<String> MIGRATIONS = List.of("V001__init.sql", "V002__auth.sql", "V003__rollups.sql", "V004__overrides.sql",
            "V005__shares.sql", "V006__access_requests.sql",
            "V007__registrations.sql", "V008__inventory.sql", "V009__watches.sql");

    private static final String MIGRATION_DIR = "/io/github/jopojp/overseer/core/storage/migration/";
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path file;
    private final Connection writer;

    private Database(Path file, Connection writer) {
        this.file = file;
        this.writer = writer;
    }

    /** Opens (creating if needed) the database and applies pending migrations. */
    public static Database open(Path file) throws SQLException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw new SQLException("cannot create directory for " + file, e);
        }
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.enforceForeignKeys(true);
        Connection writer = config.createConnection(url(file));
        try {
            migrate(writer);
            writer.setAutoCommit(false);
        } catch (SQLException | RuntimeException e) {
            writer.close();
            throw e;
        }
        return new Database(file, writer);
    }

    /** Opens a new read-only connection. Callers close it. Safe from any thread. */
    public Connection openReader() throws SQLException {
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        return config.createConnection(url(file));
    }

    /**
     * Opens a connection for low-volume control writes (logins, credentials, admin grants)
     * with auto-commit on. Callers serialize their own use. WAL serializes it against the
     * metrics writer; the busy timeout absorbs short waits (ADR-0006).
     */
    public Connection openControlWriter() throws SQLException {
        var config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.enforceForeignKeys(true);
        return config.createConnection(url(file));
    }

    /** The single writer connection (auto-commit off). Storage writer thread only. */
    Connection writer() {
        return writer;
    }

    public Path file() {
        return file;
    }

    public int schemaVersion() throws SQLException {
        return userVersion(writer);
    }

    /**
     * Starts a new uptime session: continues the uptime where the previous session ended.
     * Call once at server start, before the storage writer starts.
     */
    public SessionStart startSession(Instant wall) throws SQLException {
        int previous = 0;
        long baseUptime = 0;
        try (var st = writer.createStatement();
                var rs = st.executeQuery(
                        "SELECT session, last_uptime_ms FROM uptime_session ORDER BY session DESC LIMIT 1")) {
            if (rs.next()) {
                previous = rs.getInt(1);
                baseUptime = rs.getLong(2);
            }
        }
        int session = previous + 1;
        try (var ps = writer.prepareStatement("INSERT INTO uptime_session "
                + "(session, started_wall_ms, started_uptime_ms, last_wall_ms, last_uptime_ms) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, session);
            ps.setLong(2, wall.toEpochMilli());
            ps.setLong(3, baseUptime);
            ps.setLong(4, wall.toEpochMilli());
            ps.setLong(5, baseUptime);
            ps.executeUpdate();
        }
        writer.commit();
        return new SessionStart(session, baseUptime);
    }

    @Override
    public void close() throws SQLException {
        writer.close();
    }

    private static String url(Path file) {
        return "jdbc:sqlite:" + file.toAbsolutePath();
    }

    private static void migrate(Connection connection) throws SQLException {
        int current = userVersion(connection);
        if (current > MIGRATIONS.size()) {
            throw new SQLException("database schema version " + current
                    + " is newer than this Overseer supports (" + MIGRATIONS.size() + "); refusing to downgrade");
        }
        for (int version = current + 1; version <= MIGRATIONS.size(); version++) {
            String sql = readMigration(MIGRATIONS.get(version - 1));
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                for (String statement : splitStatements(sql)) st.execute(statement);
                st.execute("PRAGMA user_version = " + version);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new SQLException("migration " + MIGRATIONS.get(version - 1) + " failed", e);
            }
        }
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (var st = connection.createStatement(); var rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String readMigration(String name) {
        try (InputStream in = Database.class.getResourceAsStream(MIGRATION_DIR + name)) {
            if (in == null) throw new IllegalStateException("missing migration resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Splits a migration script on semicolons; strips {@code --} line comments. No triggers allowed. */
    static List<String> splitStatements(String sql) {
        var withoutComments = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            int comment = line.indexOf("--");
            withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return java.util.Arrays.stream(withoutComments.toString().split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Result of {@link #startSession}: the new session number and the uptime it continues from. */
    public record SessionStart(int session, long baseUptimeMillis) {}
}
