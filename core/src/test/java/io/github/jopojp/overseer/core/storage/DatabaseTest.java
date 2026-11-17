package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {
    @TempDir
    Path dir;

    @Test
    void migratesNewDatabaseToLatestVersion() throws SQLException {
        try (var db = Database.open(dir.resolve("nested/overseer.db"))) {
            assertEquals(Database.MIGRATIONS.size(), db.schemaVersion());
        }
    }

    @Test
    void reopeningIsIdempotent() throws SQLException {
        Path file = dir.resolve("overseer.db");
        Database.open(file).close();
        try (var db = Database.open(file)) {
            assertEquals(Database.MIGRATIONS.size(), db.schemaVersion());
        }
    }

    @Test
    void usesWriteAheadLog() throws SQLException {
        try (var db = Database.open(dir.resolve("overseer.db"));
                var reader = db.openReader();
                var st = reader.createStatement();
                var rs = st.executeQuery("PRAGMA journal_mode")) {
            rs.next();
            assertEquals("wal", rs.getString(1));
        }
    }

    @Test
    void refusesNewerSchema() throws SQLException {
        Path file = dir.resolve("overseer.db");
        try (var db = Database.open(file); var st = db.writer().createStatement()) {
            st.execute("PRAGMA user_version = 999");
            db.writer().commit();
        }
        assertThrows(SQLException.class, () -> Database.open(file));
    }

    @Test
    void sessionsContinueUptime() throws SQLException {
        Path file = dir.resolve("overseer.db");
        try (var db = Database.open(file)) {
            var first = db.startSession(Instant.parse("2026-01-01T00:00:00Z"));
            assertEquals(new Database.SessionStart(1, 0), first);
            try (var st = db.writer().createStatement()) {
                st.executeUpdate("UPDATE uptime_session SET last_uptime_ms = 7200000 WHERE session = 1");
            }
            db.writer().commit();
        }
        try (var db = Database.open(file)) {
            var second = db.startSession(Instant.parse("2026-01-02T00:00:00Z"));
            assertEquals(new Database.SessionStart(2, 7_200_000), second);
        }
    }

    @Test
    void splitsStatementsAndStripsComments() {
        var sql = "-- header\nCREATE TABLE a (x INTEGER); -- trailing\n\nCREATE TABLE b (y TEXT);\n";
        assertEquals(List.of("CREATE TABLE a (x INTEGER)", "CREATE TABLE b (y TEXT)"),
                Database.splitStatements(sql));
    }
}
